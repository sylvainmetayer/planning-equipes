package dev.sylvain.planning.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The counter behind the application's rate limits: how many times a key was
 * used since the window opened, and how long is left before it reopens.
 *
 * <p>Written once rather than in each limiter. There are five of them now —
 * {@code CodeRequestLimiter} on the access codes, {@code DeclarationRateLimiter}
 * on the declarations and {@code ColleagueLookupLimiter} on a colleague's seats,
 * in the espace, {@code McpRateLimiter} on the MCP transport and
 * {@code AffichageMuralRateLimiter} on the wall display — and they
 * guard very different abuses with the very same arithmetic. A hand-rolled copy
 * is how they would drift, and the MCP one had.</p>
 *
 * <p><b>Bounded when its keys are chosen by the caller.</b> The espace keys are
 * tokens and animateur ids, which exist in the database before they are counted.
 * A source address is whatever the network hands over, so a counter keyed by it
 * takes a ceiling ({@link #bounded(int)}): past it, a <em>new</em> key first
 * sweeps the expired windows, then evicts the oldest ones, expired or not — the
 * reasoning {@code AdminLoginLimiter} gives at length. A key already counted
 * never triggers the sweep, so a full map costs nothing to the callers it
 * already knows.</p>
 *
 * <p>In memory: the application is single-instance, and a restart is not within
 * reach of the attacker these counters aim at. Rate limiting per IP address
 * belongs to the reverse proxy — see {@code docs/securite.md}.</p>
 */
public class SlidingWindowCounter {

    private final Map<String, Window> byKey = new ConcurrentHashMap<>();

    /** Most keys tracked at once, {@code 0} for no ceiling. */
    private final int maxKeys;

    /** Unbounded: for keys the application issued itself. */
    public SlidingWindowCounter() {
        this(0);
    }

    private SlidingWindowCounter(int maxKeys) {
        this.maxKeys = maxKeys;
    }

    /** At most {@code maxKeys} keys at once: for keys the caller chooses, such as a source address. */
    public static SlidingWindowCounter bounded(int maxKeys) {
        return new SlidingWindowCounter(maxKeys);
    }

    /** Uses counted, the start of the window counting them, and the distinct elements it let through. */
    private record Window(int uses, Instant debut, Set<String> seen) {}

    /**
     * Consumes one token for {@code key}. A negative verdict carries the delay
     * left before the window reopens, ready to be used as is for
     * {@code Retry-After}.
     */
    public RateLimitVerdict use(String key, int ceiling, Duration window) {
        Instant maintenant = Instant.now();
        makeRoomFor(key, maintenant, window);
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
    public RateLimitVerdict useDistinct(String key, String element, int ceiling, Duration window) {
        Instant now = Instant.now();
        makeRoomFor(key, now, window);
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
    public void forget(String key) {
        byKey.remove(key);
    }

    /**
     * Brings the map under its ceiling before a <em>new</em> key goes in:
     * expired windows first, then the oldest ones. A key already there, or an
     * unbounded counter, costs nothing.
     */
    private void makeRoomFor(String key, Instant now, Duration window) {
        if (maxKeys <= 0 || byKey.size() < maxKeys || byKey.containsKey(key)) {
            return;
        }
        byKey.entrySet().removeIf(entry -> entry.getValue().debut().plus(window).isBefore(now));
        int excess = byKey.size() - maxKeys + 1;
        if (excess <= 0) {
            return;
        }
        byKey.entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getValue().debut()))
                .limit(excess)
                .map(Map.Entry::getKey)
                .toList()
                .forEach(byKey::remove);
    }
}
