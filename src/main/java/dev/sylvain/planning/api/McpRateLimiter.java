package dev.sylvain.planning.api;

import dev.sylvain.planning.config.ConfigAdminLogin;
import dev.sylvain.planning.config.ConfigMcp;
import dev.sylvain.planning.config.TrustedProxies;
import dev.sylvain.planning.mcp.McpApiKeyAuthenticationMechanism;
import dev.sylvain.planning.service.FailureLockout;
import dev.sylvain.planning.service.RateLimitVerdict;
import dev.sylvain.planning.service.SlidingWindowCounter;
import io.quarkus.vertx.http.runtime.filters.Filters;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

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
 * <p><b>An attempt is counted before it is made.</b> A request carrying a key
 * reserves its failure slot in {@link FailureLockout} before authentication
 * runs, and gives it back as soon as the status is written. Checking the count
 * and recording the 401 afterwards let a burst of parallel wrong keys all read
 * « no failure yet »: 120 guesses per lockout where five were promised. The slot
 * is given back on the headers, not on the end of the response, so a streaming
 * {@code GET /mcp} does not hold it for as long as it stays open.</p>
 *
 * <p><b>The proxies are the deployment's.</b> An empty
 * {@code planning.mcp.rate-limit.trusted-proxies} falls back to
 * {@code planning.auth.connexion.proxys-fiables}, in Java rather than only in the
 * property's default: a compose file that passes the variable through blank sets
 * it to the empty string, which the {@code ${A:${B:}}} fallback does not treat as
 * missing — every {@code /mcp} caller then shared the proxy's single counter.</p>
 *
 * <p>In memory and per address, like the other two limiters, on the counters
 * the application shares ({@link SlidingWindowCounter}, {@link FailureLockout}).
 * Rate limiting the whole site per IP remains the reverse proxy's job — see
 * {@code docs/securite.md}.</p>
 */
@ApplicationScoped
public class McpRateLimiter {

    /** The MCP transport: {@code /mcp} itself (streamable HTTP) and everything under it ({@code /mcp/sse}). */
    static final String MCP_PATH = "/mcp";

    /** After the security headers, before anything handles the request — as for the login lock. */
    private static final int PRIORITY = 250;

    /**
     * Hard ceiling on the number of tracked addresses, for the reason
     * {@link AdminLoginLimiter} states at length: the counters are keyed by
     * something the caller chooses. It applies to each counter separately: they
     * are fed by different events and neither should be able to evict the
     * other's entries.
     */
    private static final int MAX_ADDRESSES = 1_000;

    @Inject
    ConfigMcp config;

    @Inject
    ConfigAdminLogin loginConfig;

    private final SlidingWindowCounter requests = SlidingWindowCounter.bounded(MAX_ADDRESSES);

    private final FailureLockout wrongKeys = new FailureLockout(MAX_ADDRESSES);

    /**
     * The declared proxies, parsed once. Built here rather than lazily so a
     * malformed entry fails the boot: skipping it would leave the deployment
     * believing it had declared its proxy, while both guards quietly counted
     * every caller on the proxy's own single counter.
     */
    private volatile TrustedProxies trustedProxies = TrustedProxies.NONE;

    public void register(@Observes Filters filters) {
        trustedProxies = TrustedProxies.of(proxies(
                config.rateLimit().trustedProxies().orElse(List.of()),
                loginConfig.proxysFiables().orElse(List.of())));
        filters.register(this::apply, PRIORITY);
    }

    /**
     * The MCP list when it names anything, the login lock's otherwise: the
     * proxies are a fact about the deployment, and an entry left blank is how a
     * compose file says « not set ».
     */
    static List<String> proxies(List<String> mcp, List<String> login) {
        List<String> declared = mcp.stream().filter(entry -> !entry.isBlank()).toList();
        return declared.isEmpty() ? login : declared;
    }

    private void apply(RoutingContext context) {
        if (!isMcpPath(context.normalizedPath())) {
            context.next();
            return;
        }
        String address = ClientAddress.of(context, trustedProxies);
        int maxFailures = config.lockout().maxFailures();

        long locked =
                wrongKeys.lockoutSeconds(address, maxFailures, config.lockout().duration());
        if (locked > 0) {
            refuse(context, locked, "Trop de clés refusées : réessayez dans " + minutes(locked) + " minute(s).");
            return;
        }
        if (config.rateLimit().maxRequests() > 0) {
            RateLimitVerdict verdict = requests.use(
                    address,
                    config.rateLimit().maxRequests(),
                    config.rateLimit().window());
            if (!verdict.autorise()) {
                long wait = verdict.secondsBeforeNextTry();
                refuse(context, wait, "Trop de requêtes sur /mcp : réessayez dans " + wait + " seconde(s).");
                return;
            }
        }
        if (McpApiKeyAuthenticationMechanism.presentedKey(context, config.apiKeyHeader()) != null) {
            long wait = wrongKeys.reserve(address, maxFailures, config.lockout().duration());
            if (wait > 0) {
                refuse(context, wait, "Trop de clés refusées : réessayez dans " + minutes(wait) + " minute(s).");
                return;
            }
            AtomicBoolean settled = new AtomicBoolean();
            // Called just before the headers go out: the status is final.
            context.addHeadersEndHandler(ignore -> {
                if (settled.compareAndSet(false, true)) {
                    wrongKeys.settle(address, outcome(context), config.lockout().duration());
                }
            });
            // The headers may never be written — a connection reset first: the
            // slot is given back all the same, as an attempt that tried nothing.
            context.addEndHandler(ignore -> {
                if (settled.compareAndSet(false, true)) {
                    wrongKeys.settle(
                            address,
                            FailureLockout.Outcome.NEITHER,
                            config.lockout().duration());
                }
            });
        }
        context.next();
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
     * <p>So: {@code 401} is the policy refusing the key. {@code 403} is an admin
     * session holding no {@code mcp} role — no key was accepted or refused, so
     * it neither counts nor clears. Anything else got through authentication,
     * whatever the MCP transport then made of the body, and the run stops
     * there.</p>
     */
    private static FailureLockout.Outcome outcome(RoutingContext context) {
        return switch (context.response().getStatusCode()) {
            case 401 -> FailureLockout.Outcome.FAILURE;
            case 403 -> FailureLockout.Outcome.NEITHER;
            default -> FailureLockout.Outcome.SUCCESS;
        };
    }

    private static void refuse(RoutingContext context, long wait, String message) {
        context.response()
                .setStatusCode(429)
                .putHeader("Retry-After", String.valueOf(wait))
                .putHeader("Content-Type", "application/json;charset=UTF-8")
                .end("{\"message\":\"" + message + "\"}");
    }

    /** Seconds rounded up to whole minutes, for a wait a human is meant to read. */
    private static long minutes(long seconds) {
        return Math.max(1, (seconds + 59) / 60);
    }

    /**
     * {@code /mcp} and what hangs under it, and nothing else. A plain
     * {@code startsWith} would also catch a future {@code /mcp-console}, and the
     * REST side of the MCP page is out of reach either way — it is served under
     * {@code /api/mcp} and falls under the ordinary admin policy.
     */
    private static boolean isMcpPath(String path) {
        return path != null && (MCP_PATH.equals(path) || path.startsWith(MCP_PATH + "/"));
    }
}
