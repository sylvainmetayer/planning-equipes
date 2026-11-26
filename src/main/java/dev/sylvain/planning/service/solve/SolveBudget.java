package dev.sylvain.planning.service.solve;

/**
 * The termination one solve runs under: a time budget, and how long an
 * already feasible planning may go without improving before it stops.
 *
 * <p>Both halves may be {@code null}, and then the deployment decides — see
 * {@link SolverConfiguration#resolveSolverFactory(SolveBudget, dev.sylvain.planning.domain.PlanningEvenement)}.
 * A budget built by {@link SolveBudgetPolicy} for a job always carries both;
 * the nulls remain for the callers that only ever passed a duration (the
 * scenario harnesses, the synchronous tests), and for a job persisted before
 * the plateau was stored.</p>
 *
 * @param secondsLimit   the time budget, in seconds; {@code null} is the
 *                       deployment default
 * @param plateauSeconds the feasible-plateau bailout, in seconds, {@code 0}
 *                       for never; {@code null} is the deployment default when
 *                       the duration is the default one too, and no bailout
 *                       at all under an explicit duration — an explicit
 *                       duration without a plateau has always meant "exactly
 *                       that long"
 */
public record SolveBudget(Long secondsLimit, Long plateauSeconds) {

    /** The deployment's budget, whole. */
    public static final SolveBudget DEFAULT = new SolveBudget(null, null);

    /** A duration alone, with the plateau rule of {@link #plateauSeconds}. */
    public static SolveBudget ofSeconds(Long secondsLimit) {
        return new SolveBudget(secondsLimit, null);
    }
}
