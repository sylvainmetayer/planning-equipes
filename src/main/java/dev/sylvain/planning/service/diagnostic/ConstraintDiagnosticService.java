package dev.sylvain.planning.service.diagnostic;

import dev.sylvain.planning.domain.PlanningEvenement;

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
}
