package dev.sylvain.planning.service.solve;

/**
 * What the two stages of a feasibility-first solve ({@link FeasibilityFirstSolve},
 * ADR 0067) did, for the recap of the job and the server log.
 *
 * @param feasibilityReached      whether the first stage reached zero hard
 * @param feasibilitySeconds      time the first stage took
 * @param polishingSeconds        time the second stage took
 * @param publishedSeatsChangedAfterFeasibility published seats the first
 *                                stage's plan had moved, stability ignored
 * @param publishedSeatsChanged   the same count on the final plan: what the
 *                                second stage brought back is the difference
 * @param stoppedAfterFeasibility whether a cancel or a shutdown ended the job
 *                                after the first stage, the second never
 *                                running
 */
public record FeasibilityFirstReport(
        boolean feasibilityReached,
        long feasibilitySeconds,
        long polishingSeconds,
        int publishedSeatsChangedAfterFeasibility,
        int publishedSeatsChanged,
        boolean stoppedAfterFeasibility) {}
