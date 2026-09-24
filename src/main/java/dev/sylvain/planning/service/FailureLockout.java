package dev.sylvain.planning.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A lock on a run of failed attempts, per key: past {@code maxFailures} in a
 * row, the key is refused until {@code duration} after the last one.
 *
 * <p>Written once for the two locks that count by source address —
 * {@code AdminLoginLimiter} on the admin form and {@code McpRateLimiter} on the
 * MCP key — which had each written it by hand, eviction included.</p>
 *
 * <p><b>Counted from the last failure.</b> A refused attempt never reaches
 * authentication, so it produces no new failure, and the lock lifts a full
 * {@code duration} after the last real one: trying again while locked gains
 * nothing.</p>
 *
 * <p><b>Reserved before it is attempted</b> ({@link #reserve}), when the caller
 * learns of the failure only after the fact. Checking the count, letting the
 * attempt through and recording its failure afterwards leaves a gap: a burst of
 * attempts sent in parallel all read « no failure yet », and all of them get
 * their try before the first 401 is counted — 120 guesses per lockout where
 * five were promised. A reservation takes the slot atomically, so no more than
 * {@code maxFailures} attempts can be in flight or failed at once; each one is
 * settled ({@link #settle}) as soon as its outcome is known.</p>
 *
 * <p><b>Bounded</b>, because the key is whatever the network hands over: past
 * {@code maxKeys}, a <em>new</em> key first sweeps the expired runs, then evicts
 * the oldest ones, expired or not — losing one address its count is a far
 * smaller harm than unbounded memory on the request path. A run with attempts
 * in flight is never evicted: its reservations must be able to come back.</p>
 */
public final class FailureLockout {

    /** Consecutive failures, attempts reserved and not yet settled, and the instant of the last failure. */
    private record Run(int failures, int inFlight, Instant last) {

        Run cleared() {
            return new Run(0, inFlight, last);
        }
    }

    private final Map<String, Run> byKey = new ConcurrentHashMap<>();
    private final int maxKeys;

    public FailureLockout(int maxKeys) {
        this.maxKeys = maxKeys;
    }

    /**
     * Seconds of lockout left for {@code key}, {@code 0} when it may try again;
     * a run whose lock has lifted is forgotten on the way.
     */
    public long lockoutSeconds(String key, int maxFailures, Duration duration) {
        if (maxFailures <= 0) {
            return 0;
        }
        Run run = byKey.get(key);
        if (run == null || run.failures() < maxFailures) {
            return 0;
        }
        Instant now = Instant.now();
        if (run.last().plus(duration).isBefore(now)) {
            byKey.computeIfPresent(key, (ignore, current) -> current.inFlight() > 0 ? current.cleared() : null);
            return 0;
        }
        // Rounded up: a lock with less than a second left is still a lock, as
        // reserve() reads it.
        return Math.max(secondsLeft(run, duration, now), 1);
    }

    /**
     * Takes a slot for one attempt whose outcome will be {@link #settle settled}
     * later. Returns {@code 0} when the slot is taken; otherwise the seconds to
     * wait — the lockout left, or one second when the slots are held by attempts
     * still in flight — and nothing is reserved.
     */
    public long reserve(String key, int maxFailures, Duration duration) {
        if (maxFailures <= 0) {
            return 0;
        }
        Instant now = Instant.now();
        makeRoomFor(key, now, duration);
        long[] wait = {0};
        byKey.compute(key, (ignore, current) -> {
            Run run = live(current, now, duration);
            if (run.failures() >= maxFailures) {
                wait[0] = Math.max(secondsLeft(run, duration, now), 1);
                return run;
            }
            if (run.failures() + run.inFlight() >= maxFailures) {
                wait[0] = 1;
                return run;
            }
            return new Run(run.failures(), run.inFlight() + 1, run.last());
        });
        return wait[0];
    }

    /**
     * Gives back a slot {@link #reserve reserved}, with what the attempt turned
     * out to be: a failure lengthens the run, a success clears it, anything else
     * — no credential was actually tried — does neither.
     */
    public void settle(String key, Outcome outcome, Duration duration) {
        Instant now = Instant.now();
        byKey.computeIfPresent(key, (ignore, current) -> {
            int inFlight = Math.max(0, current.inFlight() - 1);
            Run run =
                    switch (outcome) {
                        case FAILURE -> {
                            Run live = live(current, now, duration);
                            yield new Run(live.failures() + 1, inFlight, now);
                        }
                        case SUCCESS -> new Run(0, inFlight, current.last());
                        case NEITHER -> new Run(current.failures(), inFlight, current.last());
                    };
            return run.failures() == 0 && run.inFlight() == 0 ? null : run;
        });
    }

    /** One more failure for {@code key}, for a caller that learns of it without having reserved. */
    public void recordFailure(String key, Duration duration) {
        Instant now = Instant.now();
        makeRoomFor(key, now, duration);
        byKey.compute(key, (ignore, current) -> {
            Run run = live(current, now, duration);
            return new Run(run.failures() + 1, run.inFlight(), now);
        });
    }

    /** The run stops there: a success. */
    public void clear(String key) {
        byKey.computeIfPresent(key, (ignore, current) -> current.inFlight() > 0 ? current.cleared() : null);
    }

    /** What a settled attempt turned out to be. */
    public enum Outcome {
        FAILURE,
        SUCCESS,
        NEITHER
    }

    /** The run as it stands now: a lapsed one starts again from zero, keeping its attempts in flight. */
    private static Run live(Run current, Instant now, Duration duration) {
        if (current == null) {
            return new Run(0, 0, now);
        }
        if (current.failures() > 0 && current.last().plus(duration).isBefore(now)) {
            return new Run(0, current.inFlight(), current.last());
        }
        return current;
    }

    private static long secondsLeft(Run run, Duration duration, Instant now) {
        return Duration.between(now, run.last().plus(duration)).toSeconds();
    }

    private void makeRoomFor(String key, Instant now, Duration duration) {
        if (byKey.size() < maxKeys || byKey.containsKey(key)) {
            return;
        }
        byKey.entrySet()
                .removeIf(entry -> entry.getValue().inFlight() == 0
                        && entry.getValue().last().plus(duration).isBefore(now));
        int excess = byKey.size() - maxKeys + 1;
        if (excess <= 0) {
            return;
        }
        byKey.entrySet().stream()
                .filter(entry -> entry.getValue().inFlight() == 0)
                .sorted(Comparator.comparing(entry -> entry.getValue().last()))
                .limit(excess)
                .map(Map.Entry::getKey)
                .toList()
                .forEach(byKey::remove);
    }
}
