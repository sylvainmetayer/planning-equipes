package dev.sylvain.planning.service.solve;

import ai.timefold.solver.core.api.score.HardMediumSoftScore;

/**
 * The plan a solve replaced, and whether replacing it made things worse
 * (issue #274).
 *
 * <p>A solve announces its own score and never the one it overwrote, so an
 * operator who re-solves an edition that already held a good plan reads
 * "0 hard", concludes all is well, and leaves with a worse planning than the
 * one they had. Measured on the real 2026 profile: a 600 s re-solve from
 * scratch cost 1 202 medium points against the plan already in place. The
 * safety net was there — {@link PlanSnapshotService#captureBeforeSolve()} —
 * but silent, and it expires after
 * {@code planning.snapshots.automatiques-conservees} solves.</p>
 *
 * <p>This carries no judgement about whether the solve should have run:
 * re-solving after a late change is legitimate and nothing here blocks or
 * warns beforehand. It only makes the outcome legible, and points at the
 * snapshot to restore when the answer is "that was worse".</p>
 *
 * @param snapshotId the automatic snapshot holding that plan, the one to
 *                   restore to undo the solve
 * @param score      its score, {@code null} when it could not be established
 *                   — the comparison is then simply not offered
 * @param degraded   whether the new plan scores strictly lower, hard then
 *                   medium then soft, the order Timefold itself uses
 */
public record PreviousPlan(long snapshotId, String score, boolean degraded) {

    /**
     * Compares what the solve produced against what it replaced.
     *
     * @param snapshotId  id of what
     *                    {@link PlanSnapshotService#captureBeforeSolve()}
     *                    captured, {@code null} on the first solve of an
     *                    edition (nothing to capture) or when the capture
     *                    failed
     * @param scoreBefore the score of that plan, {@code null} when it could
     *                    not be established
     * @param scoreAfter  the score the solve just produced
     * @return {@code null} when there is no previous plan to talk about
     */
    public static PreviousPlan of(Long snapshotId, String scoreBefore, String scoreAfter) {
        if (snapshotId == null) {
            return null;
        }
        return new PreviousPlan(snapshotId, scoreBefore, isDegraded(scoreBefore, scoreAfter));
    }

    /**
     * Whether {@code after} is strictly worse than {@code before}. Unparseable
     * or missing on either side means no verdict: an unreadable score must not
     * raise a false alarm about a solve that may well have improved things.
     */
    static boolean isDegraded(String before, String after) {
        HardMediumSoftScore scoreBefore = parse(before);
        HardMediumSoftScore scoreAfter = parse(after);
        if (scoreBefore == null || scoreAfter == null) {
            return false;
        }
        return scoreAfter.compareTo(scoreBefore) < 0;
    }

    /**
     * Whether {@code after} is strictly better than {@code before}. The
     * mirror of {@link #isDegraded}, with the same caution turned the other
     * way: an unreadable score on either side is no proof of an improvement,
     * so it answers {@code false} — the plan in place stands.
     */
    static boolean isImproved(String before, String after) {
        HardMediumSoftScore scoreBefore = parse(before);
        HardMediumSoftScore scoreAfter = parse(after);
        if (scoreBefore == null || scoreAfter == null) {
            return false;
        }
        return scoreAfter.compareTo(scoreBefore) > 0;
    }

    private static HardMediumSoftScore parse(String score) {
        if (score == null || score.isBlank()) {
            return null;
        }
        try {
            return HardMediumSoftScore.parseScore(score);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
