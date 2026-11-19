package dev.sylvain.planning.solver.handsearch;

import java.util.ArrayDeque;

/**
 * When a feasible plan has stopped paying for the time spent on it: the rule
 * the other search ends on (ADR 0081, 0082), kept the same here so that one
 * setting of the edition means one thing whichever engine runs.
 *
 * <p>The window opens at feasibility. A new best plan pushes the end of the
 * solve one window away when it is {@code gain} medium points better than a
 * best plan no older than the window — and each best plan serves as that
 * reference once, the oldest first, so a steady trickle that brings the gain
 * within every window keeps the solve alive, and one that does not lets it
 * end a window after the last time it did. With a gain of zero any better
 * plan pushes the end away: the strict plateau.</p>
 *
 * <p>This is Timefold's unimproved-time termination with a score-difference
 * threshold, on the medium level alone as the configuration sets it; times
 * are whatever unit the caller counts in.</p>
 */
final class Plateau {

    private final long window;
    private final long gain;

    /** The best plans not yet used as a reference: when each was found, and its medium points lost. */
    private final ArrayDeque<long[]> references = new ArrayDeque<>();

    private long safeUntil = Long.MAX_VALUE;

    /** @param window how long without the gain ends the solve; 0 or less for no plateau at all */
    Plateau(long window, long gain) {
        this.window = window;
        this.gain = gain;
    }

    /** A new best feasible plan — the first one opens the window. */
    void best(long now, long mediumLost) {
        if (window <= 0) {
            return;
        }
        if (references.isEmpty() || gain <= 0) {
            safeUntil = now + window;
        }
        while (gain > 0 && !references.isEmpty()) {
            long[] oldest = references.peekFirst();
            if (oldest[1] - mediumLost < gain || oldest[0] + window < now) {
                break;
            }
            references.removeFirst();
            safeUntil = now + window;
        }
        if (gain > 0 || references.isEmpty()) {
            references.addLast(new long[] {now, mediumLost});
        }
    }

    /** Whether the solve has outlived its last paying improvement by a whole window. */
    boolean over(long now) {
        return now > safeUntil;
    }
}
