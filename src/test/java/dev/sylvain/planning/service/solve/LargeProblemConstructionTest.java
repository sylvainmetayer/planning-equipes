package dev.sylvain.planning.service.solve;

import static org.assertj.core.api.Assertions.assertThat;

import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.constructionheuristic.ConstructionHeuristicPhaseConfig;
import ai.timefold.solver.core.config.constructionheuristic.placer.QueuedEntityPlacerConfig;
import ai.timefold.solver.core.config.heuristic.selector.move.generic.ChangeMoveSelectorConfig;
import ai.timefold.solver.core.config.score.director.ScoreDirectorFactoryConfig;
import ai.timefold.solver.core.config.solver.SolverConfig;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.solver.EligibleAnimateurMoveFilter;
import dev.sylvain.planning.solver.PlanningConstraintProvider;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LargeProblemConstructionTest {

    private static PlanningEvenement problem(int seats, int animateurs) {
        Stand stand = new Stand("S", "S", Set.of("JEUX"), 1, 1, false);
        Creneau creneau = new Creneau(1L, 1, LocalDate.of(2028, 7, 1), LocalTime.of(10, 0), LocalTime.of(13, 0));
        List<PosteAffectation> postes = new ArrayList<>();
        for (int i = 0; i < seats; i++) {
            postes.add(new PosteAffectation("P" + i, stand, creneau));
        }
        List<Animateur> roster = new ArrayList<>();
        for (int i = 0; i < animateurs; i++) {
            roster.add(new Animateur("A" + i, "P", "N", LocalDate.of(1990, 1, 1), false));
        }
        return new PlanningEvenement(creneau.getDate(), roster, postes);
    }

    /**
     * Every real edition sits far under the threshold — 3 500 seats and 153
     * animateurs is half a million pairs — and so does a month with a thousand
     * animateurs, where the exact construction still pays for itself.
     */
    @Test
    void onlyAVeryLargeProblemIsSampled() {
        assertThat(LargeProblemConstruction.applies(problem(3500, 153))).isFalse();
        assertThat(LargeProblemConstruction.applies(problem(6480, 1000))).isFalse();
        assertThat(LargeProblemConstruction.applies(problem(41370, 1000))).isTrue();
        assertThat(LargeProblemConstruction.applies(null)).isFalse();
    }

    @Test
    void theSampledConstructionReplacesTheFirstPhaseAndStillBuildsASolver() {
        SolverConfig solverConfig = SolverConfig.createFromXmlResource("solver/solverConfig.xml");
        solverConfig.setScoreDirectorFactoryConfig(
                new ScoreDirectorFactoryConfig().withConstraintProviderClass(PlanningConstraintProvider.class));
        int phases = solverConfig.getPhaseConfigList().size();

        LargeProblemConstruction.adapt(solverConfig);

        assertThat(solverConfig.getPhaseConfigList()).hasSize(phases);
        assertThat(solverConfig.getPhaseConfigList().getFirst())
                .isInstanceOfSatisfying(ConstructionHeuristicPhaseConfig.class, construction -> {
                    QueuedEntityPlacerConfig placer = (QueuedEntityPlacerConfig) construction.getEntityPlacerConfig();
                    ChangeMoveSelectorConfig change = (ChangeMoveSelectorConfig)
                            placer.getMoveSelectorConfigList().getFirst();
                    assertThat(change.getValueSelectorConfig().getSelectedCountLimit())
                            .isEqualTo(LargeProblemConstruction.CANDIDATES_PER_SEAT);
                    assertThat(change.getFilterClass()).isEqualTo(EligibleAnimateurMoveFilter.ChangeMoveFilter.class);
                });
        assertThat(SolverFactory.<PlanningEvenement>create(solverConfig).buildSolver())
                .isNotNull();
    }
}
