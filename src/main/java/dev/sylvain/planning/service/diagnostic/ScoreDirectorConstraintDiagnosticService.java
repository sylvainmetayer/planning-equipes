package dev.sylvain.planning.service.diagnostic;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import ai.timefold.solver.core.api.score.buildin.hardmediumsoft.HardMediumSoftScore;
import ai.timefold.solver.core.api.score.constraint.ConstraintMatch;
import ai.timefold.solver.core.api.score.constraint.ConstraintMatchTotal;
import ai.timefold.solver.core.api.score.stream.ConstraintJustification;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.impl.score.constraint.ConstraintMatchPolicy;
import ai.timefold.solver.core.impl.score.director.InnerScoreDirector;
import ai.timefold.solver.core.impl.score.director.ScoreDirectorFactory;
import ai.timefold.solver.core.impl.solver.DefaultSolverFactory;
import dev.sylvain.planning.domain.PlanningEvenement;

/**
 * Diagnoses through the solver's own score director, which the Community
 * edition of Timefold runs without any licence.
 *
 * <p><b>Why this exists.</b> In Timefold 2.x, {@code SolutionManager.analyze()}
 * is gated behind the Enterprise edition. Only the façade is: the constraint
 * matching machinery underneath — including the justification facts, the
 * expensive part — is ordinary Community code. Timefold's own workaround text
 * for the gated feature says as much: <i>"do not use SolutionManager's
 * analyze() method"</i>. So this class does what {@code analyze()} does, one
 * layer lower.</p>
 *
 * <p><b>Same work, not merely similar work.</b> On Timefold 1.x,
 * {@code analyze()} is a {@code calculateScore()} followed by a walk over
 * {@code getConstraintMatchTotalMap().values()}, each total folded into one
 * entry per distinct justification — which is the loop below, down to the
 * score director options (no look-up, no cloning, constraint matching with
 * justifications) and the folding (see {@link #distinctFacts}). The
 * equivalence is therefore by construction, and
 * {@code ConstraintDiagnosticServiceContractTest} keeps proving it.</p>
 *
 * <p><b>The cost, stated plainly.</b> {@code ScoreDirectorFactory},
 * {@code InnerScoreDirector} and {@code ConstraintMatchPolicy} live in
 * {@code ai.timefold.solver.core.impl}, outside the semver promise: a minor
 * Timefold bump may break this class. That is why every contact with those
 * packages is confined here, and why the contract test exists — a bump that
 * changes the behaviour fails the comparison and names the difference instead
 * of quietly reshaping five screens. The project already takes this bet in
 * {@code EligibleAnimateurMoveFilter} and {@code UnassignedPosteFilter}.</p>
 *
 * <p><b>What a Timefold 2.x migration must change here.</b> Run against 2.5.0
 * Community with no licence: {@code analyze()} throws
 * <i>"A commercial feature "Score analysis" was requested but it could not be
 * loaded"</i>, while the loop below returns the whole diagnostic — per-constraint
 * score and weight, match counts, per-match score, justification facts, and the
 * constraints that matched nothing. What the code has to change:</p>
 * <ul>
 * <li>{@code ConstraintMatchTotal} and {@code ConstraintMatch} move from
 *     {@code api.score.constraint} to {@code impl.score.constraint};</li>
 * <li>{@code ConstraintRef} loses {@code constraintName()} — it becomes a
 *     one-component record whose accessor is {@code id()}. Verified on 2.5.0
 *     that this id is the <b>bare</b> constraint name, so the swap is safe:
 *     these names are looked up in {@code ConstraintCatalog} and matched
 *     against {@code HARD_CONSTRAINT_NAMES}, and a qualified id would have
 *     broken every lookup silently rather than loudly;</li>
 * <li>{@code HardMediumSoftScore} moves to {@code api.score} and its components
 *     become {@code long} — project-wide, not specific to this class.</li>
 * </ul>
 *
 * <p>The map's key type changes too (from {@code String} to
 * {@code ConstraintRef}), which is why the loop below reads {@code values()}
 * and never a key.</p>
 */
public final class ScoreDirectorConstraintDiagnosticService implements ConstraintDiagnosticService {

    private final ScoreDirectorFactory<PlanningEvenement, HardMediumSoftScore> scoreDirectorFactory;

    public ScoreDirectorConstraintDiagnosticService(SolverFactory<PlanningEvenement> solverFactory) {
        this.scoreDirectorFactory = ((DefaultSolverFactory<PlanningEvenement>) solverFactory).getScoreDirectorFactory();
    }

    @Override
    public PlanningAnalysis analyze(PlanningEvenement solution) {
        try (InnerScoreDirector<PlanningEvenement, HardMediumSoftScore> scoreDirector = buildScoreDirector()) {
            // In place, not on a clone: callers match the facts coming back
            // against the very objects they passed in, by identity.
            scoreDirector.setWorkingSolution(solution);
            // The one calculation. It also writes the score onto the solution,
            // which is how diagnose() reads its hard score back.
            HardMediumSoftScore score = scoreDirector.calculateScore().raw();
            List<ConstraintContribution> contributions = new ArrayList<>();
            for (ConstraintMatchTotal<HardMediumSoftScore> total : scoreDirector.getConstraintMatchTotalMap().values()) {
                contributions.add(new ConstraintContribution(
                        total.getConstraintRef().constraintName(),
                        total.getScore(),
                        matchFacts(total)));
            }
            return new PlanningAnalysis(score, contributions);
        }
    }

    /**
     * The three options {@code SolutionManager.analyze()} sets, spelled out
     * rather than left to defaults: this class is only correct as long as it
     * asks for the same thing, so the reader should be able to check that here
     * instead of in a Timefold changelog.
     */
    private InnerScoreDirector<PlanningEvenement, HardMediumSoftScore> buildScoreDirector() {
        return scoreDirectorFactory.createScoreDirectorBuilder()
                .withLookUpEnabled(false)
                .withConstraintMatchPolicy(ConstraintMatchPolicy.ENABLED)
                .withExpectShadowVariablesInCorrectState(false)
                .build();
    }

    private static List<MatchFacts> matchFacts(ConstraintMatchTotal<HardMediumSoftScore> total) {
        List<ConstraintJustification> justifications = new ArrayList<>();
        for (ConstraintMatch<HardMediumSoftScore> match : total.getConstraintMatchSet()) {
            justifications.add(match.getJustification());
        }
        return distinctFacts(justifications);
    }

    /**
     * One entry per <b>distinct</b> justification, in encounter order.
     *
     * <p>This is not a tidying pass, it is parity. Asked for every match,
     * {@code SolutionManager.analyze()} does not hand back the raw match set: it
     * groups matches sharing a justification and folds them into one, so both
     * the reported matches and the match count are counted over distinct
     * justifications. Returning the raw set would report a larger count for the
     * same planning.</p>
     *
     * <p>No constraint in this project justifies itself with anything but the
     * tuple it matched on, so today the two coincide — but "coincide today" is
     * exactly the kind of equivalence that breaks quietly the first time a
     * constraint narrows its own justification.</p>
     *
     * <p>A justification's identity is its facts and nothing else — the impact
     * it carried is not part of it. So two matches over the same facts fold
     * into one whatever they weighed, which is why {@code analyze()} <em>sums</em>
     * the impacts it folds. Nothing is lost here by not doing that sum:
     * {@link MatchFacts} carries no score, and the constraint's own total
     * already includes every match.</p>
     */
    static List<MatchFacts> distinctFacts(List<ConstraintJustification> justifications) {
        Set<ConstraintJustification> seen = new LinkedHashSet<>();
        List<MatchFacts> matches = new ArrayList<>();
        for (ConstraintJustification justification : justifications) {
            if (seen.add(justification)) {
                matches.add(MatchFacts.of(justification));
            }
        }
        return matches;
    }
}
