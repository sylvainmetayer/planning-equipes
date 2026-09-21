package dev.sylvain.planning.api;

import dev.sylvain.planning.config.ConfigMcp;
import dev.sylvain.planning.config.TrustedProxies;
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
 * Rate limit on the MCP transport, per source address.
 *
 * <p>{@code /mcp} is the one authenticated surface whose credential is a
 * <b>single long-lived shared key</b> carried in a header, with no session, no
 * second factor and no user behind it. Until now nothing bounded the rate at
 * which that key could be tried, nor the rate at which a holder of it could
 * call: the tools it opens start solver runs, rewrite the referentials and read
 * every animateur's planning, so both halves are worth bounding. The login form
 * ({@link AdminLoginLimiter}) and the espace's access codes had their ceiling;
 * this is the third, on the door that was still counting nothing.</p>
 *
 * <p>Unlike the login lock, what is counted here is <b>every</b> request under
 * {@code /mcp}, authenticated or not. A rate limit that only counted failures
 * would leave a stolen key free to run at the speed of the network — and a key
 * pasted into a hosted assistant travels further than a password ever does.</p>
 *
 * <h2>Why a Vert.x filter after all</h2>
 *
 * <p>Authentication deliberately goes through the
 * {@code quarkus.http.auth.permission.*} engine rather than a filter (see
 * {@code McpApiKeyAuthenticationMechanism}), because only a policy expresses
 * "this path needs the {@code mcp} role". A rate limit expresses nothing of the
 * sort — it has to refuse before authentication even runs, which is exactly what
 * the filter chain is for. And the chain <em>does</em> see these routes: the
 * headers {@code SecurityHeadersFilter} writes are on every {@code /mcp}
 * response, filter priority 300, this one at 250.</p>
 *
 * <h2>The shape of the window</h2>
 *
 * <p>A fixed window opening on the first request of an address: past the
 * ceiling, the answer is {@code 429} with a {@code Retry-After}, and the window
 * still closes on time — a refused request does not push it back. That is the
 * opposite of the login lock, whose window runs from the last failure on
 * purpose, and the difference is what each one guards: a lock wants the attacker
 * to gain nothing by trying again, a rate limit wants a legitimate client that
 * hit the ceiling to come back at a predictable moment.</p>
 *
 * <p>In memory and per address, like the other two. Rate limiting the whole site
 * per IP remains the reverse proxy's job — see {@code docs/securite.md}.</p>
 */
@ApplicationScoped
public class McpRateLimiter {

    /** The MCP transport: {@code /mcp} itself (streamable HTTP) and everything under it ({@code /mcp/sse}). */
    static final String CHEMIN_MCP = "/mcp";

    /** After the security headers, before anything handles the request — as for the login lock. */
    private static final int PRIORITE = 250;

    /**
     * Hard ceiling on the number of tracked addresses, for the reason
     * {@link AdminLoginLimiter} states at length: the map is keyed by something
     * the caller chooses, so sweeping only the expired entries would not bound
     * it. Past the ceiling the oldest go, expired or not — losing an address its
     * counter is a far smaller harm than unbounded memory on the request path.
     */
    private static final int ADRESSES_MAX = 1_000;

    @Inject
    ConfigMcp config;

    private final Map<String, Fenetre> parAdresse = new ConcurrentHashMap<>();

    /** Requests counted for one address, and the instant its window opened. */
    private record Fenetre(int nombre, Instant debut) {}

    /**
     * The declared proxies, parsed once. Built here rather than lazily so a
     * malformed entry fails the boot: skipping it would leave the deployment
     * believing it had declared its proxy, while the limit quietly counted every
     * caller on the proxy's own single counter.
     */
    private volatile TrustedProxies proxysFiables = TrustedProxies.NONE;

    public void register(@Observes Filters filtres) {
        proxysFiables = TrustedProxies.of(config.rateLimit().trustedProxies().orElse(List.of()));
        filtres.register(this::apply, PRIORITE);
    }

    private void apply(RoutingContext contexte) {
        if (!isMcpPath(contexte.normalizedPath()) || config.rateLimit().maxRequests() <= 0) {
            contexte.next();
            return;
        }
        long attente = secondsBeforeNextTry(ClientAddress.of(contexte, proxysFiables));
        if (attente <= 0) {
            contexte.next();
            return;
        }
        contexte.response()
                .setStatusCode(429)
                .putHeader("Retry-After", String.valueOf(attente))
                .putHeader("Content-Type", "application/json;charset=UTF-8")
                .end("{\"message\":\"Trop de requêtes sur /mcp : réessayez dans " + attente + " seconde(s).\"}");
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
     * Seconds left before the window reopens, {@code 0} when the request may go
     * through — the same shape, and the same name, as the espace limiters'
     * {@code RateLimitVerdict.secondsBeforeNextTry}, and ready to be used as is
     * for {@code Retry-After}.
     */
    private long secondsBeforeNextTry(String address) {
        Duration fenetre = config.rateLimit().window();
        Instant maintenant = Instant.now();
        if (parAdresse.size() >= ADRESSES_MAX) {
            evictDown(maintenant, fenetre);
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
     * Brings the map back under its ceiling: expired windows first, then, if
     * that is not enough, the oldest ones.
     */
    private void evictDown(Instant maintenant, Duration fenetre) {
        parAdresse
                .entrySet()
                .removeIf(entree -> entree.getValue().debut().plus(fenetre).isBefore(maintenant));
        if (parAdresse.size() < ADRESSES_MAX) {
            return;
        }
        parAdresse.entrySet().stream()
                .sorted(Comparator.comparing(entree -> entree.getValue().debut()))
                .limit(Math.max(1, parAdresse.size() - ADRESSES_MAX + 1))
                .map(Map.Entry::getKey)
                .toList()
                .forEach(parAdresse::remove);
    }
}
