package dev.sylvain.planning.service.diagnostic;

import java.util.List;

import ai.timefold.solver.core.api.solver.SolverFactory;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;

/**
 * Breaks a planning's score down per constraint, with the facts justifying
 * every match — the single source every diagnostic feature draws from: the
 * Contraintes, Problèmes and Heatmap screens, the per-assignment explanation
 * ("pourquoi lui ?"), the swap and échange simulations, the repair assistant,
 * and their MCP counterparts.
 *
 * <p><b>Why this interface exists.</b> All of that used to call
 * {@code SolutionManager.analyze()} directly, in a dozen places. In Timefold
 * 2.x that method is gated behind the Enterprise edition — and not merely for
 * the diagnostic screens: the end of every solve calls it, so without a licence
 * a perfectly good solve is reported as failed. Naming the capability, rather
 * than the Timefold method that happens to provide it, is what lets the
 * implementation change without any consumer knowing.</p>
 *
 * <p>Implementations must be interchangeable: same score, same constraints,
 * same matches, same justification facts, in whatever order callers already
 * tolerate. {@code ConstraintDiagnosticServiceContractTest} is what holds them
 * to it.</p>
 */
public interface ConstraintDiagnosticService {

    /**
     * Scores {@code solution} and reports what every constraint contributed.
     *
     * <p>The solution is read <b>in place</b>, never cloned: callers rely on
     * the facts coming back being the very objects they passed in (the
     * per-assignment explanation keeps only the matches whose facts contain
     * <em>this</em> poste, by identity). Its score field is refreshed as a side
     * effect, which {@code diagnose} reads back.</p>
     */
    PlanningAnalysis analyze(PlanningEvenement solution);

    /**
     * What would happen if {@code cible} were handed to each of
     * {@code candidats} in turn — the read-only « banc de touche » question,
     * answered by the constraints and by nothing else.
     *
     * <p><b>Why this belongs here.</b> A screen that lists why an animateur
     * cannot take a seat has exactly two ways to know: restate the rules, or
     * ask the ones that are already written. The first drifts the day a
     * threshold moves — which is the whole risk issue #303 is about — so this
     * interface offers the second. Callers get constraint <i>names</i>, the
     * keys {@code ConstraintCatalog} already describes in business words, and
     * never a rule of their own.</p>
     *
     * <p>Nothing is persisted and no solve is started. {@code cible}'s
     * occupant is restored before returning, so the caller's planning comes
     * back exactly as it was handed over — like
     * {@code PlanningService.simulateSwap}'s in-place substitution, and for the
     * same reason (the planning is a per-request payload, never shared).</p>
     *
     * <p>The default implementation is the naive one, one full
     * {@link #analyze} per candidate. {@link ScoreDirectorConstraintDiagnosticService}
     * overrides it with a single score director stepped incrementally, which
     * is what makes asking the question for all ~150 animateurs affordable on
     * a real plan.</p>
     *
     * @param candidats evaluated in order; an animateur already on {@code cible}
     *                  is not filtered out, the caller decides who is worth asking about
     */
    default List<AffectationHypothesis> hypotheses(PlanningEvenement solution, PosteAffectation cible,
            List<Animateur> candidats) {
        return AffectationHypothesis.byFullAnalysis(this, solution, cible, candidats);
    }

    /**
     * The single, explicit switch point between implementations — the one place
     * in the codebase that knows there is more than one.
     */
    static ConstraintDiagnosticService of(ConstraintDiagnosticMode mode,
            SolverFactory<PlanningEvenement> solverFactory) {
        return switch (mode) {
            case SCORE_DIRECTOR -> new ScoreDirectorConstraintDiagnosticService(solverFactory);
            case SOLUTION_MANAGER -> new SolutionManagerConstraintDiagnosticService(solverFactory);
        };
    }
}
