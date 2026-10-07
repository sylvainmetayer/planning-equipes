package dev.sylvain.planning.service.solve;

import static org.assertj.core.api.Assertions.assertThat;

import ai.timefold.solver.core.api.score.HardMediumSoftScore;
import ai.timefold.solver.core.config.solver.SolverConfig;
import ai.timefold.solver.core.config.solver.termination.TerminationCompositionStyle;
import ai.timefold.solver.core.config.solver.termination.TerminationConfig;
import dev.sylvain.planning.domain.ParametresQualite;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.service.EmptyReferenceData;
import java.util.List;
import java.util.stream.IntStream;
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

    /**
     * The gain is sized on the problem: the deployment's per-seat value times
     * the seats being solved (ADR 0081) — 0.25 a seat, so 1 014 medium points
     * for 4 057 seats. The test profile pins the property to zero (its
     * scenarios must stop where they always did), so the value is given here
     * under the profile's own prefix, which outranks the file.
     */
    @Test
    void thePlateauGainFollowsTheSeatsOfTheProblem() {
        String profiled = "%test." + SolverConfiguration.PLATEAU_GAIN_PROPERTY;
        System.setProperty(profiled, "0.25");
        try {
            SolverConfiguration sized = new SolverConfiguration(
                    900L,
                    180L,
                    ParametresQualite.EMPLACEMENTS_DISTINCTS_PAR_JOUR_MAX_PAR_DEFAUT,
                    new EmptyReferenceData(),
                    ConfigProvider.getConfig());
            PlanningEvenement problem = new PlanningEvenement(
                    null,
                    List.of(),
                    IntStream.range(0, 4057)
                            .mapToObj(i -> new PosteAffectation("p" + i, null, null))
                            .toList());

            assertThat(sized.plateauGainMedium(problem)).isEqualTo(1014L);
            assertThat(sized.plateauGainMedium(null)).isZero();
            TerminationConfig plateau = sized.solverConfigFor(SolveBudget.DEFAULT, problem)
                    .getTerminationConfig()
                    .getTerminationConfigList()
                    .get(0);
            assertThat(plateau.getUnimprovedSecondsSpentLimit()).isEqualTo(180L);
            assertThat(HardMediumSoftScore.parseScore(plateau.getUnimprovedScoreDifferenceThreshold()))
                    .isEqualTo(HardMediumSoftScore.of(0, 1014, Long.MIN_VALUE));
            // Without a problem to size it on, the plateau stays strict.
            assertThat(sized.solverConfigFor(SolveBudget.DEFAULT)
                            .getTerminationConfig()
                            .getTerminationConfigList()
                            .get(0)
                            .getUnimprovedScoreDifferenceThreshold())
                    .isNull();
        } finally {
            System.clearProperty(profiled);
        }
    }

    /**
     * Only the seats the search can move size the gain: the frozen past and
     * the locks are pinned, and a re-solve late in the event would otherwise
     * be asked a gain only the whole plan could give.
     */
    @Test
    void pinnedSeatsDoNotSizeThePlateauGain() {
        String profiled = "%test." + SolverConfiguration.PLATEAU_GAIN_PROPERTY;
        System.setProperty(profiled, "0.25");
        try {
            SolverConfiguration sized = new SolverConfiguration(
                    900L,
                    180L,
                    ParametresQualite.EMPLACEMENTS_DISTINCTS_PAR_JOUR_MAX_PAR_DEFAUT,
                    new EmptyReferenceData(),
                    ConfigProvider.getConfig());
            List<PosteAffectation> postes = IntStream.range(0, 4000)
                    .mapToObj(i -> new PosteAffectation("p" + i, null, null))
                    .toList();
            postes.subList(0, 2400).forEach(poste -> poste.setVerrouille(true));

            assertThat(sized.plateauGainMedium(new PlanningEvenement(null, List.of(), postes)))
                    .isEqualTo(400L);
        } finally {
            System.clearProperty(profiled);
        }
    }

    /** Zero keeps the strict plateau: no threshold at all. */
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
}
