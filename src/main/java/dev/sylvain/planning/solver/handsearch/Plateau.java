package dev.sylvain.planning.solver.handsearch;

import java.util.ArrayDeque;

/**
 * When a search has stopped paying: the plateau of the configuration, kept
 * the way the other search keeps it, so that one setting means one thing
 * under both engines.
 *
 * <p>The clock starts with the search — the plan the construction leaves is
 * not a reference. Every better plan after that, feasible or not, pushes the
 * end one window away when it beats a best plan no older than the window by
 * a hard point or by {@code gain} medium points, whatever the soft ones do —
 * and each best plan serves as that
 * reference once, the oldest first, so a steady trickle that brings the gain
 * within every window keeps the solve alive, and one that does not lets it
 * end a window after the last time it did. With a gain of zero any better
 * plan pushes the end away: the strict plateau.</p>
 *
 * <p>This follows the source of Timefold's two unimproved-time terminations
 * (with and without a score-difference threshold) in a solve whose
 * construction phase is empty; whether the plan is feasible, which the
 * configuration asks as well, is the caller's question: this class says the
 * window is spent, never that the solve may end. Times are whatever unit the
 * caller counts in.</p>
 */
final class Plateau {

    private final long window;
    private final long gain;

    /** The best plans not yet used as a reference: when each was found, its hard and its medium points lost. */
    private final ArrayDeque<long[]> references = new ArrayDeque<>();

    private long safeUntil;

    /**
     * @param start  when the search starts
     * @param window how long the search may go without paying, zero or less for no plateau at all
     * @param gain   the medium points a better plan must bring to count, zero or less for any better plan
     */
    Plateau(long start, long window, long gain) {
        this.window = window;
        this.gain = gain;
        this.safeUntil = window <= 0 ? Long.MAX_VALUE : start + window;
    }

    /** A better plan was found at {@code now}: its hard points and its medium points lost. */
    void best(long now, long hard, long medium) {
        if (window <= 0) {
            return;
        }
        if (gain <= 0) {
            safeUntil = now + window;
            return;
        }
        while (!references.isEmpty()) {
            long[] oldest = references.peekFirst();
            boolean paid = oldest[1] > hard || (oldest[1] == hard && oldest[2] - medium >= gain);
            if (!paid || oldest[0] + window < now) {
                break;
            }
            references.removeFirst();
            safeUntil = now + window;
        }
        references.addLast(new long[] {now, hard, medium});
    }

    /** Whether the search has outlived its last paying improvement by a whole window. */
    boolean over(long now) {
        // The strict plateau ends on the window itself, the other one past it: as in the other search.
        return gain <= 0 ? now >= safeUntil : now > safeUntil;
    }
}
