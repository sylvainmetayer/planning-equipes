package dev.sylvain.planning.service.solve;

/**
 * The termination one solve runs under: a time budget, and how long an
 * already feasible planning may go without improving before it stops.
 *
 * <p>Both halves may be {@code null}, and then the deployment decides — see
 * {@link SolverConfiguration#resolveSolverFactory(SolveBudget, dev.sylvain.planning.domain.PlanningEvenement)}.
 * A budget built by {@link SolveBudgetPolicy} for a job always carries both;
 * the nulls remain for the callers that only ever passed a duration (the
 * scenario harnesses, the synchronous tests), and for a queued job persisted
 * before the plateau was stored.</p>
 *
 * @param secondsLimit   the time budget, in seconds; {@code null} is the
 *                       deployment default
 * @param plateauSeconds the feasible-plateau bailout, in seconds, {@code 0}
 *                       for never; {@code null} is the deployment default when
 *                       the duration is the default one too, and no bailout
 *                       at all under an explicit duration — an explicit
 *                       duration without a plateau has always meant "exactly
 *                       that long"
 * @param cappedFrom     what the edition stored where a ceiling cut it (a
 *                       ceiling lowered since, or a scenario imported above
 *                       it), or {@code null} when the budget is what it asked
 *                       for; carried as values rather than as a sentence, so
 *                       each reader says it in its own language
 */
public record SolveBudget(Long secondsLimit, Long plateauSeconds, CappedFrom cappedFrom) {

    /** The deployment's budget, whole. */
    public static final SolveBudget DEFAULT = new SolveBudget(null, null, null);

    /** A duration alone, with the plateau rule of {@link #plateauSeconds}. */
    public static SolveBudget ofSeconds(Long secondsLimit) {
        return new SolveBudget(secondsLimit, null, null);
    }

    /**
     * Why the budget differs from what the edition stored, as a French
     * sentence — the MCP answer and the logs. The screen renders its own from
     * {@link #cappedFrom}. {@code null} when nothing was cut.
     */
    public String warning() {
        if (cappedFrom == null) {
            return null;
        }
        StringBuilder warning = new StringBuilder();
        if (cappedFrom.secondsLimit() != null && secondsLimit != null) {
            appendCap(warning, "La durée enregistrée pour cette édition", cappedFrom.secondsLimit(), secondsLimit);
        }
        if (cappedFrom.plateauSeconds() != null && plateauSeconds != null) {
            appendCap(
                    warning,
                    "L'arrêt sur plateau enregistré pour cette édition",
                    cappedFrom.plateauSeconds(),
                    plateauSeconds);
        }
        return warning.isEmpty() ? null : warning.toString();
    }

    private static void appendCap(StringBuilder warning, String what, long stored, long ceiling) {
        if (!warning.isEmpty()) {
            warning.append(' ');
        }
        warning.append(what)
                .append(" (")
                .append(SolveBudgetPolicy.humanDuration(stored))
                .append(") dépasse le plafond de l'instance : la résolution tourne au plafond, ")
                .append(SolveBudgetPolicy.humanDuration(ceiling))
                .append('.');
    }

    /**
     * The values the edition stored that a ceiling cut, each {@code null} when
     * that half ran as stored. The ceiling itself is the budget's own value
     * for that half.
     */
    public record CappedFrom(Long secondsLimit, Long plateauSeconds) {

        /** {@code null} rather than an empty cut, so "nothing was cut" has one spelling. */
        static CappedFrom of(Long secondsLimit, Long plateauSeconds) {
            return secondsLimit == null && plateauSeconds == null ? null : new CappedFrom(secondsLimit, plateauSeconds);
        }
    }
}
