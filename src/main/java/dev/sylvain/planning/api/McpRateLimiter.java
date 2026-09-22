package dev.sylvain.planning.api;

import dev.sylvain.planning.config.ConfigMcp;
import dev.sylvain.planning.config.TrustedProxies;
import dev.sylvain.planning.mcp.McpApiKeyAuthenticationMechanism;
import io.quarkus.vertx.http.runtime.filters.Filters;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Two guards on the MCP transport, both counted per source address.
 *
 * <p>{@code /mcp} is the one authenticated surface whose credential is a
 * <b>single long-lived shared key</b> carried in a header, with no session, no
 * second factor and no user behind it. The tools it opens start solver runs,
 * rewrite the referentials and read every animateur's planning. Two different
 * abuses follow from that, and one ceiling cannot bound both:</p>
 *
 * <ul>
 * <li><b>the rate</b> — every request under {@code /mcp} is counted,
 * authenticated or not, against {@code planning.mcp.rate-limit.*}. A ceiling
 * that only counted failures would leave a <em>stolen</em> key free to run at
 * the speed of the network, and a key pasted into a hosted assistant travels
 * further than a password ever does;</li>
 * <li><b>the guessing</b> — a run of <em>wrong</em> keys locks the address out
 * for {@code planning.mcp.lockout.duration}, the same shape as
 * {@link AdminLoginLimiter} on the admin form. The rate ceiling alone leaves
 * two thousand tries an hour from one address; this makes a run of failures
 * cost time instead of nothing. Neither of them is what makes the key hard to
 * guess — that is its entropy — but a key somebody chose short stops being a
 * free target.</li>
 * </ul>
 *
 * <p><b>Only a request that presented a key can fail this way.</b> A request
 * carrying none gets its {@code 401} and counts for nothing: a client not yet
 * configured probes before it is set up, and locking it out for that would
 * punish the one case that is not an attack. A request that authenticates
 * clears the run, so a wrong header corrected on the next try costs nothing
 * either.</p>
 *
 * <h2>Why a Vert.x filter after all</h2>
 *
 * <p>Authentication deliberately goes through the
 * {@code quarkus.http.auth.permission.*} engine rather than a filter (see
 * {@code McpApiKeyAuthenticationMechanism}), because only a policy expresses
 * "this path needs the {@code mcp} role". Neither guard here expresses anything
 * of the sort — both have to refuse before authentication even runs, which is
 * exactly what the filter chain is for. And the chain <em>does</em> see these
 * routes: the headers {@code SecurityHeadersFilter} writes are on every
 * {@code /mcp} response, filter priority 300, this one at 250.</p>
 *
 * <h2>The two windows, and why they differ</h2>
 *
 * <p>The rate window is <b>fixed, opening on the first request</b> of an
 * address: past the ceiling the answer is {@code 429} with a
 * {@code Retry-After}, and the window still closes on time — a refused request
 * does not push it back, so a legitimate client that hit the ceiling knows when
 * to come back.</p>
 *
 * <p>The lockout window runs from the <b>last failure</b>, on purpose and like
 * the admin lock: a blocked request never reaches authentication, so it
 * produces no new failure, and the lock does lift a full duration after the
 * last real attempt. There, trying again while blocked must gain nothing.</p>
 *
 * <p>The lockout is checked first: it is the longer refusal, and a locked
 * address should not also spend rate tokens it will never use.</p>
 *
 * <p>In memory and per address, like the other two limiters. Rate limiting the
 * whole site per IP remains the reverse proxy's job — see
 * {@code docs/securite.md}.</p>
 */
@ApplicationScoped
public class McpRateLimiter {

    /** The MCP transport: {@code /mcp} itself (streamable HTTP) and everything under it ({@code /mcp/sse}). */
    static final String CHEMIN_MCP = "/mcp";

    /** After the security headers, before anything handles the request — as for the login lock. */
    private static final int PRIORITE = 250;

    /**
     * Hard ceiling on the number of tracked addresses, for the reason
     * {@link AdminLoginLimiter} states at length: the maps are keyed by
     * something the caller chooses, so sweeping only the expired entries would
     * not bound them. Past the ceiling the oldest go, expired or not — losing an
     * address its counter is a far smaller harm than unbounded memory on the
     * request path. It applies to each map separately: they are fed by different
     * events and neither should be able to evict the other's entries.
     */
    private static final int ADRESSES_MAX = 1_000;

    @Inject
    ConfigMcp config;

    private final Map<String, Fenetre> parAdresse = new ConcurrentHashMap<>();

    private final Map<String, Echecs> echecsParAdresse = new ConcurrentHashMap<>();

    /** Requests counted for one address, and the instant its window opened. */
    private record Fenetre(int nombre, Instant debut) {}

    /** Consecutive wrong keys from one address, and the instant of the last one. */
    private record Echecs(int nombre, Instant dernier) {}

    /**
     * The declared proxies, parsed once. Built here rather than lazily so a
     * malformed entry fails the boot: skipping it would leave the deployment
     * believing it had declared its proxy, while both guards quietly counted
     * every caller on the proxy's own single counter.
     */
    private volatile TrustedProxies proxysFiables = TrustedProxies.NONE;

    public void register(@Observes Filters filtres) {
        proxysFiables = TrustedProxies.of(config.rateLimit().trustedProxies().orElse(List.of()));
        filtres.register(this::apply, PRIORITE);
    }

    private void apply(RoutingContext contexte) {
        if (!isMcpPath(contexte.normalizedPath())) {
            contexte.next();
            return;
        }
        String address = ClientAddress.of(contexte, proxysFiables);

        long verrou = lockoutSeconds(address);
        if (verrou > 0) {
            refuse(contexte, verrou, "Trop de clés refusées : réessayez dans " + minutes(verrou) + " minute(s).");
            return;
        }
        long attente = secondsBeforeNextTry(address);
        if (attente > 0) {
            refuse(contexte, attente, "Trop de requêtes sur /mcp : réessayez dans " + attente + " seconde(s).");
            return;
        }
        contexte.addEndHandler(issue -> {
            if (issue.succeeded()) {
                recordOutcome(contexte, address);
            }
        });
        contexte.next();
    }

    /**
     * What the answer says about the key that was presented.
     *
     * <p>Read off the <b>status</b>, and deliberately not off
     * {@code context.user()}: Quarkus sets an identity on the routing context
     * even for a request it is about to refuse, so {@code user() != null} is
     * true on the 401s too. The first version of this method used it as the
     * success test — the way {@link AdminLoginLimiter} does — and cleared the
     * run on every single attempt, which counted nothing at all. It is not a
     * usable signal here.</p>
     *
     * <p>So: {@code 401} is the policy refusing, counted only when the request
     * actually carried a key (see the class javadoc). {@code 403} is an admin
     * session holding no {@code mcp} role — no key was tried, so it neither
     * counts nor clears. Anything else got through authentication, whatever the
     * MCP transport then made of the body, and the run stops there.</p>
     */
    private void recordOutcome(RoutingContext contexte, String address) {
        int statut = contexte.response().getStatusCode();
        if (statut != 401) {
            if (statut != 403) {
                echecsParAdresse.remove(address);
            }
            return;
        }
        if (McpApiKeyAuthenticationMechanism.presentedKey(contexte, config.apiKeyHeader()) == null) {
            return;
        }
        Instant maintenant = Instant.now();
        Duration blocage = config.lockout().duration();
        if (echecsParAdresse.size() >= ADRESSES_MAX) {
            evictDown(echecsParAdresse, Echecs::dernier, maintenant, blocage);
        }
        echecsParAdresse.compute(address, (ignore, courant) -> {
            if (courant == null || courant.dernier().plus(blocage).isBefore(maintenant)) {
                return new Echecs(1, maintenant);
            }
            return new Echecs(courant.nombre() + 1, maintenant);
        });
    }

    /** Seconds of lockout left, {@code 0} when the address may present a key again. */
    private long lockoutSeconds(String address) {
        if (config.lockout().maxFailures() <= 0) {
            return 0;
        }
        Echecs echecs = echecsParAdresse.get(address);
        if (echecs == null || echecs.nombre() < config.lockout().maxFailures()) {
            return 0;
        }
        long restant = Duration.between(
                        Instant.now(), echecs.dernier().plus(config.lockout().duration()))
                .toSeconds();
        if (restant <= 0) {
            echecsParAdresse.remove(address);
            return 0;
        }
        return Math.max(restant, 1);
    }

    private void refuse(RoutingContext contexte, long attente, String message) {
        contexte.response()
                .setStatusCode(429)
                .putHeader("Retry-After", String.valueOf(attente))
                .putHeader("Content-Type", "application/json;charset=UTF-8")
                .end("{\"message\":\"" + message + "\"}");
    }

    /** Seconds rounded up to whole minutes, for a wait a human is meant to read. */
    private static long minutes(long secondes) {
        return Math.max(1, (secondes + 59) / 60);
    }

    /**
     * {@code /mcp} and what hangs under it, and nothing else. A plain
     * {@code startsWith} would also catch a future {@code /mcp-console}, and the
     * REST side of the MCP page is out of reach either way — it is served under
     * {@code /api/mcp} and falls under the ordinary admin policy.
     */
    private static boolean isMcpPath(String chemin) {
        return chemin != null && (CHEMIN_MCP.equals(chemin) || chemin.startsWith(CHEMIN_MCP + "/"));
    }

    /**
     * Seconds left before the rate window reopens, {@code 0} when the request
     * may go through — the same shape, and the same name, as the espace
     * limiters' {@code RateLimitVerdict.secondsBeforeNextTry}, and ready to be
     * used as is for {@code Retry-After}.
     */
    private long secondsBeforeNextTry(String address) {
        if (config.rateLimit().maxRequests() <= 0) {
            return 0;
        }
        Duration fenetre = config.rateLimit().window();
        Instant maintenant = Instant.now();
        if (parAdresse.size() >= ADRESSES_MAX) {
            evictDown(parAdresse, Fenetre::debut, maintenant, fenetre);
        }
        Fenetre apres = parAdresse.compute(address, (ignore, courante) -> {
            if (courante == null || courante.debut().plus(fenetre).isBefore(maintenant)) {
                return new Fenetre(1, maintenant);
            }
            return new Fenetre(courante.nombre() + 1, courante.debut());
        });
        if (apres.nombre() <= config.rateLimit().maxRequests()) {
            return 0;
        }
        return Math.max(
                Duration.between(maintenant, apres.debut().plus(fenetre)).toSeconds(), 1);
    }

    /**
     * Brings one map back under the ceiling: expired entries first, then, if
     * that is not enough, the oldest ones. Written once for the two maps —
     * {@code instant} is what each record calls its own timestamp, and it is
     * the only thing that differs.
     */
    private static <V> void evictDown(
            Map<String, V> parAdresse,
            java.util.function.Function<V, Instant> instant,
            Instant maintenant,
            Duration duree) {
        parAdresse
                .entrySet()
                .removeIf(entree -> instant.apply(entree.getValue()).plus(duree).isBefore(maintenant));
        if (parAdresse.size() < ADRESSES_MAX) {
            return;
        }
        parAdresse.entrySet().stream()
                .sorted(Comparator.comparing(entree -> instant.apply(entree.getValue())))
                .limit(Math.max(1, parAdresse.size() - ADRESSES_MAX + 1))
                .map(Map.Entry::getKey)
                .toList()
                .forEach(parAdresse::remove);
    }
}
