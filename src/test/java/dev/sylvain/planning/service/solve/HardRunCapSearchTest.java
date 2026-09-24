package dev.sylvain.planning.service.solve;

import static org.assertj.core.api.Assertions.assertThat;

import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.heuristic.selector.move.composite.UnionMoveSelectorConfig;
import ai.timefold.solver.core.config.heuristic.selector.move.generic.RuinRecreateMoveSelectorConfig;
import ai.timefold.solver.core.config.localsearch.LocalSearchPhaseConfig;
import ai.timefold.solver.core.config.score.director.ScoreDirectorFactoryConfig;
import ai.timefold.solver.core.config.solver.SolverConfig;
import dev.sylvain.planning.domain.ConstraintToggle;
import dev.sylvain.planning.domain.ParametresQualite;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.solver.PlanningConstraintProvider;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class HardRunCapSearchTest {

    private static final String HARD_RUN_RULE = "maxJoursConsecutifsTravaillesDur";

    private static PlanningEvenement problem(List<ConstraintToggle> toggles) {
        PlanningEvenement problem = new PlanningEvenement(LocalDate.of(2026, 7, 6), List.of(), List.of());
        problem.setParametresQualite(List.of(new ParametresQualite()));
        problem.setConstraintsDesactivees(toggles);
        return problem;
    }

    /** The rule ships off (ADR 0045): an edition that leaves it so keeps the search it had. */
    @Test
    void appliesOnlyWhenTheHardRunRuleIsSwitchedOn() {
        assertThat(HardRunCapSearch.applies(problem(List.of()))).isFalse();
        assertThat(HardRunCapSearch.applies(problem(List.of(new ConstraintToggle(HARD_RUN_RULE, false)))))
                .isFalse();
        assertThat(HardRunCapSearch.applies(
                        problem(List.of(new ConstraintToggle("maxJoursConsecutifsTravailles", true)))))
                .as("the medium form alone")
                .isFalse();
        assertThat(HardRunCapSearch.applies(problem(List.of(new ConstraintToggle(HARD_RUN_RULE, true)))))
                .isTrue();
        assertThat(HardRunCapSearch.applies(null)).isFalse();
    }

    @Test
    void makesTheRuinAndRecreateOfTheFeasibilityPhaseRareAndStillBuildsASolver() {
        SolverConfig solverConfig = SolverConfig.createFromXmlResource("solver/solverConfig.xml");
        solverConfig.setScoreDirectorFactoryConfig(
                new ScoreDirectorFactoryConfig().withConstraintProviderClass(PlanningConstraintProvider.class));

        HardRunCapSearch.adapt(solverConfig);

        LocalSearchPhaseConfig feasibility = solverConfig.getPhaseConfigList().stream()
                .filter(LocalSearchPhaseConfig.class::isInstance)
                .map(LocalSearchPhaseConfig.class::cast)
                .findFirst()
                .orElseThrow();
        UnionMoveSelectorConfig union = (UnionMoveSelectorConfig) feasibility.getMoveSelectorConfig();
        assertThat(union.getMoveSelectorList())
                .filteredOn(RuinRecreateMoveSelectorConfig.class::isInstance)
                .singleElement()
                .satisfies(selector -> assertThat(selector.getFixedProbabilityWeight())
                        .isEqualTo(HardRunCapSearch.RUIN_RECREATE_WEIGHT));
        assertThat(union.getMoveSelectorList())
                .filteredOn(selector -> !(selector instanceof RuinRecreateMoveSelectorConfig))
                .allSatisfy(selector ->
                        assertThat(selector.getFixedProbabilityWeight()).isEqualTo(1.0));
        assertThat(SolverFactory.<PlanningEvenement>create(solverConfig).buildSolver())
                .isNotNull();
    }
}
