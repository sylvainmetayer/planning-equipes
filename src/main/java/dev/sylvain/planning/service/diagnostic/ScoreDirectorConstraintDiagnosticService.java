package dev.sylvain.planning.service.diagnostic;

import java.util.ArrayList;
import java.util.List;

import ai.timefold.solver.core.api.score.buildin.hardmediumsoft.HardMediumSoftScore;
import ai.timefold.solver.core.api.score.constraint.ConstraintMatch;
import ai.timefold.solver.core.api.score.constraint.ConstraintMatchTotal;
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
 * {@code analyze()} is exactly a {@code calculateScore()} followed by a walk
 * over {@code getConstraintMatchTotalMap().values()} — the loop below. Same
 * score director options (no look-up, no cloning, constraint matching with
 * justifications), same single score calculation, same map. The equivalence is
 * therefore by construction, and
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
 * <p><b>What a Timefold 2.x migration must change here</b>, and nowhere else:
 * {@code ConstraintMatchTotal} and {@code ConstraintMatch} move from
 * {@code api.score.constraint} to {@code impl.score.constraint} — two import
 * lines. The map's key type changes too (from {@code String} to
 * {@code ConstraintRef}), which is why the loop below reads
 * {@code values()} and never a key.</p>
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
        List<MatchFacts> matches = new ArrayList<>();
        for (ConstraintMatch<HardMediumSoftScore> match : total.getConstraintMatchSet()) {
            matches.add(MatchFacts.of(match.getJustification()));
        }
        return matches;
    }
}
