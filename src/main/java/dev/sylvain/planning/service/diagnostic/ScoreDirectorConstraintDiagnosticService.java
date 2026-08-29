package dev.sylvain.planning.service.diagnostic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ai.timefold.solver.core.api.score.HardMediumSoftScore;
import ai.timefold.solver.core.impl.score.constraint.ConstraintMatch;
import ai.timefold.solver.core.impl.score.constraint.ConstraintMatchTotal;
import ai.timefold.solver.core.api.score.stream.ConstraintJustification;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.impl.score.constraint.ConstraintMatchPolicy;
import ai.timefold.solver.core.impl.score.director.InnerScoreDirector;
import ai.timefold.solver.core.impl.score.director.ScoreDirectorFactory;
import ai.timefold.solver.core.impl.solver.DefaultSolverFactory;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;

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

    /**
     * The genuine planning variable of {@code PosteAffectation}, named here
     * because {@code beforeVariableChanged} takes it as a string. A rename of
     * the field without a rename here throws at the first probe rather than
     * corrupting anything silently.
     */
    private static final String VARIABLE_ANIMATEUR = "animateur";

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
                        total.getConstraintRef().id(),
                        total.getScore(),
                        matchFacts(total)));
            }
            return new PlanningAnalysis(score, contributions);
        }
    }

    /**
     * The same answer as {@link ConstraintDiagnosticService#hypotheses}'s
     * default, for a fraction of the cost: one score director, built once, and
     * stepped from candidate to candidate the way local search steps from move
     * to move.
     *
     * <p><b>Why the fast path is worth its lines.</b> The default pays a full
     * {@code analyze()} per candidate — a fresh constraint session plus a
     * from-scratch score calculation over every poste. On the reference
     * scenario that is ~150 of them for a single screen. Here the session is
     * built once and each candidate costs only the incremental recalculation
     * of what the one changed variable touches, which is exactly the operation
     * the solver performs millions of times per solve.</p>
     *
     * <p><b>Why it cannot drift from the rules.</b> It runs the constraints.
     * The only thing this method decides is which per-constraint totals got
     * worse; the totals themselves are the score director's.</p>
     *
     * <p><b>Why the seat comes back untouched.</b> Every substitution is
     * wrapped in the {@code beforeVariableChanged}/{@code afterVariableChanged}
     * pair the score director requires, and the original occupant is restored
     * through the same pair in a {@code finally}, followed by one last
     * {@code calculateScore()} so the solution stops carrying the last
     * candidate's score — skipping either half of the pair is what score
     * corruption is made of, and skipping the recalculation leaves a stale
     * score the next reader cannot tell from a real one.
     * {@code AffectationHypothesisTest} asserts the parity with the default
     * implementation, and that the plan and its score both come back
     * unchanged.</p>
     *
     * <p>Justifications are deliberately <b>not</b> tracked
     * ({@link ConstraintMatchPolicy#ENABLED_WITHOUT_JUSTIFICATIONS}): building
     * the facts of every match is the expensive half of constraint matching,
     * and this method reports names and scores, never facts.</p>
     */
    @Override
    public List<AffectationHypothesis> hypotheses(PlanningEvenement solution, PosteAffectation cible,
            List<Animateur> candidats) {
        Animateur initial = cible.getAnimateur();
        try (InnerScoreDirector<PlanningEvenement, HardMediumSoftScore> scoreDirector = buildProbeScoreDirector()) {
            scoreDirector.setWorkingSolution(solution);
            List<AffectationHypothesis> hypotheses = new ArrayList<>(candidats.size());
            try {
                // The baseline is the seat EMPTY, never its current occupant —
                // see the interface's javadoc for the answer that gets wrong.
                assign(scoreDirector, cible, null);
                HardMediumSoftScore avant = scoreDirector.calculateScore().raw();
                Map<String, HardMediumSoftScore> totalsBefore = totals(scoreDirector);
                for (Animateur candidat : candidats) {
                    assign(scoreDirector, cible, candidat);
                    HardMediumSoftScore apres = scoreDirector.calculateScore().raw();
                    hypotheses.add(new AffectationHypothesis(candidat.getId(), apres, apres.subtract(avant),
                            AffectationHypothesis.worsened(totalsBefore, totals(scoreDirector))));
                }
            } finally {
                assign(scoreDirector, cible, initial);
                // Restoring the variable is not restoring the plan: the score
                // director writes the score onto the solution, so the last
                // candidate's would stay on it, indistinguishable from a real one.
                scoreDirector.calculateScore();
            }
            return List.copyOf(hypotheses);
        }
    }

    /** The one mutation, always through the score director so its match totals stay in step. */
    private static void assign(InnerScoreDirector<PlanningEvenement, HardMediumSoftScore> scoreDirector,
            PosteAffectation cible, Animateur animateur) {
        scoreDirector.beforeVariableChanged(cible, VARIABLE_ANIMATEUR);
        cible.setAnimateur(animateur);
        scoreDirector.afterVariableChanged(cible, VARIABLE_ANIMATEUR);
    }

    private static Map<String, HardMediumSoftScore> totals(
            InnerScoreDirector<PlanningEvenement, HardMediumSoftScore> scoreDirector) {
        Map<String, HardMediumSoftScore> totals = new HashMap<>();
        for (ConstraintMatchTotal<HardMediumSoftScore> total : scoreDirector.getConstraintMatchTotalMap().values()) {
            totals.put(total.getConstraintRef().id(), total.getScore());
        }
        return totals;
    }

    /**
     * Same options as {@link #buildScoreDirector()} but without justification
     * tracking, which this probe has no reader for.
     */
    private InnerScoreDirector<PlanningEvenement, HardMediumSoftScore> buildProbeScoreDirector() {
        return scoreDirectorFactory.createScoreDirectorBuilder()
                .withLookUpEnabled(false)
                .withConstraintMatchPolicy(ConstraintMatchPolicy.ENABLED_WITHOUT_JUSTIFICATIONS)
                .withExpectShadowVariablesInCorrectState(false)
                .build();
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
