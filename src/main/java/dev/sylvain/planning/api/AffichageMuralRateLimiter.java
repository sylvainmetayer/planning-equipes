package dev.sylvain.planning.api;

import dev.sylvain.planning.config.ConfigAdminLogin;
import dev.sylvain.planning.config.ConfigAffichageMural;
import dev.sylvain.planning.config.TrustedProxies;
import dev.sylvain.planning.service.FailureLockout;
import dev.sylvain.planning.service.RateLimitVerdict;
import dev.sylvain.planning.service.SlidingWindowCounter;
import dev.sylvain.planning.service.mural.AffichageMuralService;
import io.quarkus.vertx.http.runtime.filters.Filters;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Two guards on the public wall display route ({@code GET /api/mural/{token}}),
 * which is open without a session: the token in its path is the credential.
 *
 * <ul>
 * <li><b>Refused reads, per address.</b> Only a read answered {@code 404} —
 * an unknown or revoked token — counts. Past
 * {@code planning.affichage-mural.max-refused} of them, the address is locked
 * out of every token it has not been seen reading successfully, until
 * {@code window} after its last refusal (the shape of {@link McpRateLimiter}'s
 * lockout on a run of wrong keys). Walking the token space then costs time
 * rather than nothing, and never reaches the database.</li>
 * <li><b>Reads, per valid link</b> — a generous ceiling
 * ({@code max-reads-per-link}) bounding what one leaked address can draw.</li>
 * </ul>
 *
 * <p><b>Why not every request, per address.</b> Behind a proxy that has not
 * been declared ({@code CONNEXION_PROXYS_FIABLES} blank, the default), every
 * request carries the proxy's own address: a ceiling on all requests let thirty
 * junk requests a minute, from anywhere, silence every screen of the event. So
 * a token this application has answered {@code 200} for is remembered (by its
 * SHA-256, never in clear) and goes through a locked address: junk from the
 * same address never blanks a television. After a restart that memory is empty,
 * and so are the counters — the screens, reading every minute, are known again
 * long before a flood can lock anything.</p>
 *
 * <p>A Vert.x filter rather than a check in the resource, for the reason
 * {@link McpRateLimiter} gives: the refusal must come before anything resolves
 * the token or reads the plan. Same address rule as the other per-address
 * guards ({@link ClientAddress}, the declared proxies of the login lock), and
 * the same bounded counters, since an address is chosen by the caller.</p>
 */
@ApplicationScoped
public class AffichageMuralRateLimiter {

    /** The public prefix, and the only one: see {@code quarkus.http.auth.permission.affichage-mural}. */
    static final String MURAL_PATH = "/api/mural/";

    /** After the security headers, before anything handles the request — as for the other limiters. */
    private static final int PRIORITY = 250;

    /** Most addresses, and most valid links, tracked at once: see {@link AdminLoginLimiter}. */
    private static final int MAX_KEYS = 1_000;

    private final ConfigAffichageMural config;

    private final ConfigAdminLogin loginConfig;

    private final FailureLockout refused = new FailureLockout(MAX_KEYS);

    private final SlidingWindowCounter reads = SlidingWindowCounter.bounded(MAX_KEYS);

    /** Hashes of the tokens last answered {@code 200}, with when. */
    private final Map<String, Instant> validTokens = new ConcurrentHashMap<>();

    private volatile TrustedProxies trustedProxies = TrustedProxies.NONE;

    @Inject
    public AffichageMuralRateLimiter(ConfigAffichageMural config, ConfigAdminLogin loginConfig) {
        this.config = config;
        this.loginConfig = loginConfig;
    }

    public void register(@Observes Filters filters) {
        trustedProxies = TrustedProxies.of(loginConfig.proxysFiables().orElse(List.of()));
        filters.register(this::apply, PRIORITY);
    }

    private void apply(RoutingContext context) {
        String path = context.normalizedPath();
        if (path == null || !path.startsWith(MURAL_PATH)) {
            context.next();
            return;
        }
        String address = ClientAddress.of(context, trustedProxies);
        String tokenHash = AffichageMuralService.hash(path.substring(MURAL_PATH.length()));
        boolean known = validTokens.containsKey(tokenHash);

        if (!known && config.maxRefused() > 0) {
            long locked = refused.lockoutSeconds(address, config.maxRefused(), config.window());
            if (locked > 0) {
                refuse(context, locked);
                return;
            }
        }
        if (known && config.maxReadsPerLink() > 0) {
            RateLimitVerdict verdict = reads.use(tokenHash, config.maxReadsPerLink(), config.window());
            if (!verdict.autorise()) {
                refuse(context, verdict.secondsBeforeNextTry());
                return;
            }
        }
        // Called just before the headers go out: the status is final.
        context.addHeadersEndHandler(ignore -> settle(context.response().getStatusCode(), address, tokenHash));
        context.next();
    }

    private void settle(int status, String address, String tokenHash) {
        if (status == 200) {
            remember(tokenHash);
        } else if (status == 404) {
            // A revoked link loses its pass at its first refusal.
            validTokens.remove(tokenHash);
            if (config.maxRefused() > 0) {
                refused.recordFailure(address, config.window());
            }
        }
    }

    /** Bounded like the counters: past the ceiling, the links read longest ago are forgotten. */
    private void remember(String tokenHash) {
        if (validTokens.size() >= MAX_KEYS && !validTokens.containsKey(tokenHash)) {
            validTokens.entrySet().stream()
                    .sorted(Map.Entry.comparingByValue(Comparator.naturalOrder()))
                    .limit(validTokens.size() - MAX_KEYS + 1L)
                    .map(Map.Entry::getKey)
                    .toList()
                    .forEach(validTokens::remove);
        }
        validTokens.put(tokenHash, Instant.now());
    }

    private static void refuse(RoutingContext context, long wait) {
        context.response()
                .setStatusCode(429)
                .putHeader("Retry-After", String.valueOf(wait))
                .putHeader("Cache-Control", "no-store")
                .putHeader("Content-Type", "application/json;charset=UTF-8")
                .end("{\"message\":\"Trop de requêtes : réessayez dans " + wait + " seconde(s).\"}");
    }
}
