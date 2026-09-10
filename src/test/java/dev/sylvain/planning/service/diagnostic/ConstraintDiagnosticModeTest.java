package dev.sylvain.planning.service.diagnostic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.score.director.ScoreDirectorFactoryConfig;
import ai.timefold.solver.core.config.solver.SolverConfig;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.solver.PlanningConstraintProvider;
import org.junit.jupiter.api.Test;

/**
 * The switch between the two implementations, which is a configuration choice
 * and not a runtime detection: nothing probes for an Enterprise licence,
 * because the implementations are equivalent rather than graded.
 */
class ConstraintDiagnosticModeTest {

    /**
     * A deployment that configures nothing must get the implementation that
     * needs no licence — the whole point of the change.
     */
    @Test
    void theDefaultIsTheScoreDirector() {
        assertThat(ConstraintDiagnosticMode.DEFAULT).isEqualTo(ConstraintDiagnosticMode.SCORE_DIRECTOR);
        assertThat(ConstraintDiagnosticService.of(ConstraintDiagnosticMode.DEFAULT, solverFactory()))
                .isInstanceOf(ScoreDirectorConstraintDiagnosticService.class);
    }

    @Test
    void solutionManagerModeSelectsTheAnalyzeImplementation() {
        assertThat(ConstraintDiagnosticService.of(ConstraintDiagnosticMode.SOLUTION_MANAGER, solverFactory()))
                .isInstanceOf(SolutionManagerConstraintDiagnosticService.class);
    }

    @Test
    void everyModeIsReachableFromItsConfigurationValue() {
        for (ConstraintDiagnosticMode mode : ConstraintDiagnosticMode.values()) {
            assertThat(ConstraintDiagnosticMode.fromConfigValue(mode.configValue()))
                    .isEqualTo(mode);
        }
    }

    /**
     * A typo must name the accepted values rather than silently running the
     * default: a deployment that meant to select the oracle and got the default
     * would report a comparison it never made.
     */
    @Test
    void anUnknownValueFailsAndListsWhatIsAccepted() {
        assertThatThrownBy(() -> ConstraintDiagnosticMode.fromConfigValue("enterprise"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(ConstraintDiagnosticMode.CONFIG_PROPERTY)
                .hasMessageContaining("score-director")
                .hasMessageContaining("solution-manager");
    }

    private static SolverFactory<PlanningEvenement> solverFactory() {
        SolverConfig solverConfig = SolverConfig.createFromXmlResource("solver/solverConfig.xml");
        solverConfig.setScoreDirectorFactoryConfig(
                new ScoreDirectorFactoryConfig().withConstraintProviderClass(PlanningConstraintProvider.class));
        return SolverFactory.create(solverConfig);
    }
}
