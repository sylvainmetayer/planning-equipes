package dev.sylvain.planning.service.espace;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The counter behind the espace's rate limits: how many times a key was used
 * since the window opened, and how long is left before it reopens.
 *
 * <p>Written once rather than in each limiter. There are three of them now —
 * {@link CodeRequestLimiter} on the access codes,
 * {@link DeclarationRateLimiter} on the declarations and
 * {@link ColleagueLookupLimiter} on a colleague's seats — and they guard very
 * different abuses with the very same arithmetic. A hand-rolled copy is how
 * they would drift.</p>
 *
 * <p>In memory, like the {@code McpResource} lockout: the application is
 * single-instance, and a restart is not within reach of the attacker these
 * counters aim at. Rate limiting per IP address belongs to the reverse proxy —
 * see {@code docs/securite.md}.</p>
 */
class SlidingWindowCounter {

    private final Map<String, Window> byKey = new ConcurrentHashMap<>();

    /** Uses counted, the start of the window counting them, and the distinct elements it let through. */
    private record Window(int uses, Instant debut, Set<String> seen) {}

    /**
     * Consumes one token for {@code key}. A negative verdict carries the delay
     * left before the window reopens, ready to be used as is for
     * {@code Retry-After}.
     */
    RateLimitVerdict use(String key, int ceiling, Duration window) {
        Instant maintenant = Instant.now();
        Window apres = byKey.compute(key, (ignore, courante) -> {
            if (courante == null || courante.debut().plus(window).isBefore(maintenant)) {
                return new Window(1, maintenant, Set.of());
            }
            return new Window(courante.uses() + 1, courante.debut(), courante.seen());
        });
        if (apres.uses() <= ceiling) {
            return RateLimitVerdict.ok();
        }
        return refused(maintenant, apres, window);
    }

    /**
     * Same window, counting <b>distinct</b> {@code element}s rather than uses:
     * one already let through in the window is free again, and a new one past
     * the ceiling is refused without being remembered. A person looking for a
     * swap comes back to the same few colleagues; a script walking the roster
     * asks for every one of them once.
     */
    RateLimitVerdict useDistinct(String key, String element, int ceiling, Duration window) {
        Instant now = Instant.now();
        Window after = byKey.compute(key, (ignore, current) -> {
            Window live = current == null || current.debut().plus(window).isBefore(now)
                    ? new Window(0, now, Set.of())
                    : current;
            if (live.seen().contains(element) || live.seen().size() >= ceiling) {
                return live;
            }
            Set<String> seen = new HashSet<>(live.seen());
            seen.add(element);
            return new Window(live.uses() + 1, live.debut(), Set.copyOf(seen));
        });
        if (after.seen().contains(element)) {
            return RateLimitVerdict.ok();
        }
        return refused(now, after, window);
    }

    private static RateLimitVerdict refused(Instant now, Window window, Duration length) {
        long restant = Duration.between(now, window.debut().plus(length)).toSeconds();
        return new RateLimitVerdict(false, Math.max(restant, 1));
    }

    /** The run of uses with no follow-up stops there. */
    void forget(String key) {
        byKey.remove(key);
    }
}
