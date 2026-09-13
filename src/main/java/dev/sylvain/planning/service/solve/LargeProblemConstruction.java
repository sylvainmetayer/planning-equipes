package dev.sylvain.planning.service.solve;

import ai.timefold.solver.core.config.constructionheuristic.ConstructionHeuristicPhaseConfig;
import ai.timefold.solver.core.config.constructionheuristic.placer.QueuedEntityPlacerConfig;
import ai.timefold.solver.core.config.heuristic.selector.common.SelectionCacheType;
import ai.timefold.solver.core.config.heuristic.selector.common.SelectionOrder;
import ai.timefold.solver.core.config.heuristic.selector.entity.EntitySelectorConfig;
import ai.timefold.solver.core.config.heuristic.selector.entity.EntitySorterManner;
import ai.timefold.solver.core.config.heuristic.selector.move.generic.ChangeMoveSelectorConfig;
import ai.timefold.solver.core.config.heuristic.selector.value.ValueSelectorConfig;
import ai.timefold.solver.core.config.phase.PhaseConfig;
import ai.timefold.solver.core.config.solver.SolverConfig;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.solver.EligibleAnimateurMoveFilter;
import java.util.ArrayList;
import java.util.List;

/**
 * The construction heuristic of a very large problem, sampled.
 *
 * <p>{@code solverConfig.xml} builds the first plan with a first fit
 * decreasing that scores every seat against every animateur. Its cost follows
 * seats × animateurs, and a little more as the plan fills: 185 s for 6 480 seats
 * and a thousand animateurs, 50 min 48 s for 41 370 seats and the same thousand
 * (the {@code extreme-02} and {@code extreme-09} scenarios). The multi-threaded
 * solving that would share it out is Enterprise-only.</p>
 *
 * <p>Past {@link #THRESHOLD} seat-candidate pairs, the same placer — seats still
 * taken hardest first, the eligibility filter still applied — scores only
 * {@link #CANDIDATES_PER_SEAT} eligible animateurs drawn at random for each seat. The
 * first plan is worse on the medium level, and the time it saves goes to the
 * local search that polishes it; below the threshold, which every real edition
 * sits under, nothing changes. The trade-off and its measurements are in
 * {@code docs/decisions}.</p>
 */
final class LargeProblemConstruction {

    /**
     * Seats × (animateurs + the empty value) from which the construction is
     * sampled: about where the exact construction passes ten minutes. Below it
     * the exact one wins — on 6.5 million pairs, 300 s ended at -5 521 medium
     * with it and -5 938 sampled, the three minutes it costs repaid by a better
     * first plan. Above it, time wins: 41 million pairs reach zero hard in
     * 3 min 30 s sampled, 50 min 48 s exact.
     */
    static final long THRESHOLD = 15_000_000L;

    /**
     * Animateurs scored for each seat once sampled — counted after the
     * eligibility filter, not before. Counted on the draws, a seat whose whole
     * draw was filtered out had no move at all, and Timefold ends the whole
     * phase on a step with no move: every later seat stayed empty.
     */
    static final long CANDIDATES_PER_SEAT = 50L;

    private static final String PLACER_ENTITY = "placerEntity";

    private LargeProblemConstruction() {}

    static boolean applies(PlanningEvenement problem) {
        if (problem == null || problem.getPostes() == null || problem.getAnimateurs() == null) {
            return false;
        }
        return (long) problem.getPostes().size() * (problem.getAnimateurs().size() + 1L) >= THRESHOLD;
    }

    /** Replaces the first construction heuristic phase of {@code solverConfig} with the sampled one. */
    static void adapt(SolverConfig solverConfig) {
        List<PhaseConfig> phases = new ArrayList<>(solverConfig.getPhaseConfigList());
        for (int index = 0; index < phases.size(); index++) {
            if (phases.get(index) instanceof ConstructionHeuristicPhaseConfig) {
                phases.set(index, sampledConstruction());
                solverConfig.setPhaseConfigList(phases);
                return;
            }
        }
        throw new IllegalStateException("solverConfig.xml has no construction heuristic phase to sample");
    }

    private static ConstructionHeuristicPhaseConfig sampledConstruction() {
        ChangeMoveSelectorConfig change = new ChangeMoveSelectorConfig()
                .withEntitySelectorConfig(EntitySelectorConfig.newMimicSelectorConfig(PLACER_ENTITY))
                .withValueSelectorConfig(new ValueSelectorConfig())
                .withSelectionOrder(SelectionOrder.RANDOM)
                .withSelectedCountLimit(CANDIDATES_PER_SEAT);
        change.setFilterClass(EligibleAnimateurMoveFilter.ChangeMoveFilter.class);
        return new ConstructionHeuristicPhaseConfig()
                .withEntityPlacerConfig(new QueuedEntityPlacerConfig()
                        .withEntitySelectorConfig(new EntitySelectorConfig()
                                .withId(PLACER_ENTITY)
                                .withCacheType(SelectionCacheType.PHASE)
                                .withSelectionOrder(SelectionOrder.SORTED)
                                .withSorterManner(EntitySorterManner.DESCENDING))
                        .withMoveSelectorConfigList(List.of(change)));
    }
}
