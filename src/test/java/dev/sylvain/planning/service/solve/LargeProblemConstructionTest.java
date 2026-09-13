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
                    assertThat(change.getSelectedCountLimit()).isEqualTo(LargeProblemConstruction.CANDIDATES_PER_SEAT);
                    assertThat(change.getFilterClass()).isEqualTo(EligibleAnimateurMoveFilter.ChangeMoveFilter.class);
                });
        assertThat(SolverFactory.<PlanningEvenement>create(solverConfig).buildSolver())
                .isNotNull();
    }

    /**
     * The sampled construction draws its candidates blind, then filters them. A
     * seat whose whole draw is filtered out, and whose draw missed the empty
     * value too, used to have no doable move — and Timefold then ends the whole
     * phase, not just that seat, leaving every later seat empty. Twenty seats
     * on twenty dates with thirty animateurs available out of a thousand each,
     * and one date nobody is available on: every seat that can be held is.
     */
    @Test
    void aSeatWhoseWholeDrawIsFilteredOutDoesNotEndTheConstruction() {
        Stand stand = new Stand("S", "S", Set.of("JEUX"), 1, 1, false);
        LocalDate debut = LocalDate.of(2028, 7, 1);
        List<Animateur> roster = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            roster.add(new Animateur("A" + i, "P", "N", LocalDate.of(1990, 1, 1), false));
        }
        List<PosteAffectation> postes = new ArrayList<>();
        for (int jour = 0; jour < 21; jour++) {
            LocalDate date = debut.plusDays(jour);
            Creneau creneau = new Creneau((long) jour + 1, jour + 1, date, LocalTime.of(10, 0), LocalTime.of(13, 0));
            postes.add(new PosteAffectation("P" + jour, stand, creneau));
            for (int i = 0; i < roster.size(); i++) {
                boolean disponible = jour < 20 && i % 1000 >= jour * 40 && i % 1000 < jour * 40 + 30;
                if (!disponible) {
                    roster.get(i).getJoursIndisponibles().add(date);
                }
            }
        }
        PlanningEvenement probleme = new PlanningEvenement(debut, roster, postes);

        SolverConfig solverConfig = SolverConfig.createFromXmlResource("solver/solverConfig.xml");
        solverConfig.setScoreDirectorFactoryConfig(
                new ScoreDirectorFactoryConfig().withConstraintProviderClass(PlanningConstraintProvider.class));
        LargeProblemConstruction.adapt(solverConfig);
        solverConfig.setPhaseConfigList(
                List.of(solverConfig.getPhaseConfigList().getFirst()));

        PlanningEvenement construit = SolverFactory.<PlanningEvenement>create(solverConfig)
                .buildSolver()
                .solve(probleme);

        assertThat(construit.getPostes())
                .filteredOn(poste -> poste.getCreneau().getJour() <= 20)
                .allSatisfy(poste -> assertThat(poste.getAnimateur())
                        .as("seat %s", poste.getId())
                        .isNotNull());
        assertThat(construit.getPostes())
                .filteredOn(poste -> poste.getCreneau().getJour() == 21)
                .allSatisfy(poste -> assertThat(poste.getAnimateur()).isNull());
    }
}
