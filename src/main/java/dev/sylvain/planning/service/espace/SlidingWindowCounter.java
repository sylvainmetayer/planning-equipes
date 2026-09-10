package dev.sylvain.planning.service.espace;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The counter behind the espace's rate limits: how many times a key was used
 * since the window opened, and how long is left before it reopens.
 *
 * <p>Written once rather than in each limiter. There are two of them now —
 * {@link CodeRequestLimiter} on the access codes and
 * {@link DeclarationRateLimiter} on the declarations — and they guard two very
 * different abuses with the very same arithmetic. A second hand-rolled copy is
 * how the two would drift.</p>
 *
 * <p>In memory, like the {@code McpResource} lockout: the application is
 * single-instance, and a restart is not within reach of the attacker these
 * counters aim at. Rate limiting per IP address belongs to the reverse proxy —
 * see {@code docs/securite.md}.</p>
 */
class SlidingWindowCounter {

    private final Map<String, Window> byKey = new ConcurrentHashMap<>();

    /** Uses counted, and the start of the window counting them. */
    private record Window(int uses, Instant debut) {
    }

    /**
     * Consumes one token for {@code key}. A negative verdict carries the delay
     * left before the window reopens, ready to be used as is for
     * {@code Retry-After}.
     */
    RateLimitVerdict use(String key, int ceiling, Duration window) {
        Instant maintenant = Instant.now();
        Window apres = byKey.compute(key, (ignore, courante) -> {
            if (courante == null || courante.debut().plus(window).isBefore(maintenant)) {
                return new Window(1, maintenant);
            }
            return new Window(courante.uses() + 1, courante.debut());
        });
        if (apres.uses() <= ceiling) {
            return RateLimitVerdict.ok();
        }
        long restant = Duration.between(maintenant, apres.debut().plus(window)).toSeconds();
        return new RateLimitVerdict(false, Math.max(restant, 1));
    }

    /** The run of uses with no follow-up stops there. */
    void forget(String key) {
        byKey.remove(key);
    }
}
