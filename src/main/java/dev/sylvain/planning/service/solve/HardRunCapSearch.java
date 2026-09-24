package dev.sylvain.planning.service.solve;

import ai.timefold.solver.core.config.heuristic.selector.move.MoveSelectorConfig;
import ai.timefold.solver.core.config.heuristic.selector.move.composite.UnionMoveSelectorConfig;
import ai.timefold.solver.core.config.heuristic.selector.move.generic.RuinRecreateMoveSelectorConfig;
import ai.timefold.solver.core.config.localsearch.LocalSearchPhaseConfig;
import ai.timefold.solver.core.config.solver.SolverConfig;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.solver.WeekRelocationMoveIteratorFactory;
import org.jboss.logging.Logger;

/**
 * The feasibility search of an edition that holds its run of days hard.
 *
 * <p>With {@code maxJoursConsecutifsTravaillesDur} on and a tight cap, what the
 * plan runs short of is <b>person-days</b>, not hours: every animateur owes a
 * day off in each window of (cap + 1) days, and a day held in two halves by two
 * people spends two of them. A hole is then filled by moving someone's day off,
 * a chain through other days that {@link WeekRelocationMoveIteratorFactory}
 * builds and the ruin-and-recreate move of {@code solverConfig.xml} cannot:
 * that one rebuilds the seats at the hole's hour. It also costs a construction
 * heuristic per evaluation — about 150 ms against 0.3 ms for the other moves —
 * so drawn one time in six it takes nearly all of the phase: on
 * {@code festival-hivernal} held at six days, 29 evaluations a second with it,
 * a thousand once it is rare.</p>
 *
 * <p>Here it stays in the union at weight {@link #RUIN_RECREATE_WEIGHT} instead
 * of the others' 1.0, so the hour-level chains it exists for
 * (ADR 0025, a re-solve after publication) remain reachable, and the time goes
 * to the chains through the week (ADR 0049). With the rule off, which is how
 * it ships, nothing changes.</p>
 */
final class HardRunCapSearch {

    private static final Logger LOG = Logger.getLogger(HardRunCapSearch.class);

    /**
     * Weight of the ruin-and-recreate selector against 1.0 for each of the
     * others: drawn about once in two hundred and fifty moves instead of once in
     * six. Removing it reached feasibility sooner still on the edition measured;
     * keeping it rare keeps the hour-level chain of ADR 0025 within reach. See
     * ADR 0049.
     */
    static final double RUIN_RECREATE_WEIGHT = 0.02;

    private HardRunCapSearch() {}

    static boolean applies(PlanningEvenement problem) {
        return problem != null && WeekRelocationMoveIteratorFactory.hardRunCap(problem) > 0;
    }

    /**
     * Lowers the weight of the ruin-and-recreate selector of the <b>first</b>
     * local search phase — the feasibility phase — of {@code solverConfig}, when
     * it sits directly in that phase's union as {@code solverConfig.xml} writes
     * it. Any other shape leaves the configuration as it is and says so in the
     * log rather than failing the solve: the search is then the one every other
     * edition runs, slower under the rule but correct. {@code HardRunCapSearchTest}
     * holds the XML to the expected shape, so the build breaks before that happens.
     */
    static void adapt(SolverConfig solverConfig) {
        LocalSearchPhaseConfig feasibility = solverConfig.getPhaseConfigList().stream()
                .filter(LocalSearchPhaseConfig.class::isInstance)
                .map(LocalSearchPhaseConfig.class::cast)
                .findFirst()
                .orElse(null);
        if (feasibility != null && feasibility.getMoveSelectorConfig() instanceof UnionMoveSelectorConfig union) {
            for (MoveSelectorConfig<?> selector : union.getMoveSelectorList()) {
                if (selector instanceof RuinRecreateMoveSelectorConfig ruinRecreate) {
                    ruinRecreate.setFixedProbabilityWeight(RUIN_RECREATE_WEIGHT);
                    return;
                }
            }
        }
        LOG.warn("solverConfig.xml: no ruin-and-recreate selector directly in the union of the first local search"
                + " phase; the hard run of days is searched without lowering it");
    }
}
