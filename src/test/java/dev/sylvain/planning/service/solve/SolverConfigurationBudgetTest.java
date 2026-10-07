package dev.sylvain.planning.service.solve;

import static org.assertj.core.api.Assertions.assertThat;

import ai.timefold.solver.core.api.score.HardMediumSoftScore;
import ai.timefold.solver.core.config.solver.SolverConfig;
import ai.timefold.solver.core.config.solver.termination.TerminationCompositionStyle;
import ai.timefold.solver.core.config.solver.termination.TerminationConfig;
import dev.sylvain.planning.domain.ParametresQualite;
import dev.sylvain.planning.service.EmptyReferenceData;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

/** What a {@link SolveBudget} becomes in Timefold's termination, on a deployment of 900 s with a 300 s plateau. */
class SolverConfigurationBudgetTest {

    private final SolverConfiguration configuration = new SolverConfiguration(
            900L,
            300L,
            ParametresQualite.EMPLACEMENTS_DISTINCTS_PAR_JOUR_MAX_PAR_DEFAUT,
            new EmptyReferenceData(),
            ConfigProvider.getConfig());

    /** A duration off the default keeps its plateau, still gated on feasibility. */
    @Test
    void aNonDefaultDurationKeepsItsFeasiblePlateau() {
        TerminationConfig termination = configuration
                .solverConfigFor(new SolveBudget(1800L, 120L, null))
                .getTerminationConfig();

        assertThat(termination.getSecondsSpentLimit()).isEqualTo(1800L);
        assertThat(termination.getTerminationConfigList()).singleElement().satisfies(plateau -> {
            assertThat(plateau.getBestScoreFeasible()).isTrue();
            assertThat(plateau.getUnimprovedSecondsSpentLimit()).isEqualTo(120L);
            assertThat(plateau.getTerminationCompositionStyle()).isEqualTo(TerminationCompositionStyle.AND);
        });
    }

    /**
     * The deployment's gain threshold, when set, turns the strict plateau into
     * « less than this many medium points over the window »: Timefold's
     * score-difference threshold on the medium level alone, soft left free.
     */
    @Test
    void aPlateauGainJudgesTheWindowOnItsMediumPoints() {
        SolverConfig solverConfig = SolverConfig.createFromXmlResource("solver/solverConfig.xml");

        SolverConfiguration.applyTermination(solverConfig, 900L, 120L, 50L);

        assertThat(solverConfig.getTerminationConfig().getTerminationConfigList())
                .singleElement()
                .satisfies(plateau -> {
                    assertThat(plateau.getBestScoreFeasible()).isTrue();
                    assertThat(plateau.getUnimprovedSecondsSpentLimit()).isEqualTo(120L);
                    assertThat(HardMediumSoftScore.parseScore(plateau.getUnimprovedScoreDifferenceThreshold()))
                            .isEqualTo(HardMediumSoftScore.of(0, 50, Long.MIN_VALUE));
                });
    }

    /** Zero — the shipped default until measured otherwise — keeps the strict plateau: no threshold at all. */
    @Test
    void aZeroGainKeepsTheStrictPlateau() {
        SolverConfig solverConfig = SolverConfig.createFromXmlResource("solver/solverConfig.xml");

        SolverConfiguration.applyTermination(solverConfig, 900L, 120L, 0L);

        assertThat(solverConfig.getTerminationConfig().getTerminationConfigList())
                .singleElement()
                .extracting(TerminationConfig::getUnimprovedScoreDifferenceThreshold)
                .isNull();
    }

    @Test
    void aZeroPlateauLeavesThePlainTimeBudget() {
        TerminationConfig termination =
                configuration.solverConfigFor(new SolveBudget(1800L, 0L, null)).getTerminationConfig();

        assertThat(termination.getSecondsSpentLimit()).isEqualTo(1800L);
        assertThat(termination.getTerminationConfigList()).isNullOrEmpty();
    }

    @Test
    void theDefaultBudgetCarriesTheDeploymentPlateau() {
        TerminationConfig termination =
                configuration.solverConfigFor(SolveBudget.DEFAULT).getTerminationConfig();

        assertThat(termination.getSecondsSpentLimit()).isEqualTo(900L);
        assertThat(termination.getTerminationConfigList())
                .singleElement()
                .extracting(TerminationConfig::getUnimprovedSecondsSpentLimit)
                .isEqualTo(300L);
    }

    /**
     * The case that once made the plateau disappear altogether: the test
     * profile's two-second plateau cutting a large scenario solved under a
     * bigger explicit budget. A duration given alone, off the default, still
     * runs its whole budget — only an edition's own plateau shortens it.
     */
    @Test
    void anExplicitDurationWithoutPlateauDoesNotInheritTheAmbientOne() {
        TerminationConfig termination =
                configuration.solverConfigFor(SolveBudget.ofSeconds(60L)).getTerminationConfig();

        assertThat(termination.getSecondsSpentLimit()).isEqualTo(60L);
        assertThat(termination.getTerminationConfigList()).isNullOrEmpty();
    }

    @Test
    void theSharedFactoryServesOnlyTheDefaultBudget() {
        assertThat(configuration.resolveSolverFactory(new SolveBudget(900L, 300L, null)))
                .isSameAs(configuration.resolveSolverFactory(SolveBudget.DEFAULT));
        assertThat(configuration.resolveSolverFactory(new SolveBudget(900L, 60L, null)))
                .isNotSameAs(configuration.resolveSolverFactory(SolveBudget.DEFAULT));
    }
}
