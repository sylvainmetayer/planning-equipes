package dev.sylvain.planning.service.solve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import dev.sylvain.planning.domain.ParametresSolveur;
import org.junit.jupiter.api.Test;

/** How a launch and an edition's settings become a job's budget, under the operator's ceilings. */
class SolveBudgetPolicyTest {

    /** Default 15 min with a 5 min plateau, at most 1 h and 30 min. */
    private static final SolveBudgetPolicy POLICY = new SolveBudgetPolicy(new SolverBudgetBounds(900, 300, 3600, 1800));

    @Test
    void anEditionThatSetNothingRunsTheDeploymentBudget() {
        SolveBudget budget = POLICY.forSolve(null, new ParametresSolveur());

        assertThat(budget.secondsLimit()).isEqualTo(900);
        assertThat(budget.plateauSeconds()).isEqualTo(300);
        assertThat(budget.warning()).isNull();
    }

    /** The case the forced 0 used to break: a non-default duration no longer switches the plateau off. */
    @Test
    void anEditionDurationKeepsTheDefaultPlateau() {
        SolveBudget budget = POLICY.forSolve(null, new ParametresSolveur(1800));

        assertThat(budget.secondsLimit()).isEqualTo(1800);
        assertThat(budget.plateauSeconds()).isEqualTo(300);
    }

    @Test
    void theEditionsPlateauAppliesWhateverTheDuration() {
        assertThat(POLICY.forSolve(null, new ParametresSolveur(2400, 120, false))
                        .plateauSeconds())
                .isEqualTo(120);
        assertThat(POLICY.forSolve(600L, new ParametresSolveur(2400, 120, false)))
                .isEqualTo(new SolveBudget(600L, 120L, null));
        assertThat(POLICY.forSolve(null, new ParametresSolveur(null, 0, false)).plateauSeconds())
                .isZero();
    }

    /** « Exactly that long » — how the scenario harnesses and the test profile stay apart. */
    @Test
    void anExplicitDurationWithoutAnEditionPlateauRunsItsWholeBudget() {
        assertThat(POLICY.forSolve(1200L, new ParametresSolveur()).plateauSeconds())
                .isZero();
        assertThat(POLICY.forSolve(900L, new ParametresSolveur()).plateauSeconds())
                .isEqualTo(300);
    }

    @Test
    void aDurationAskedAboveTheCeilingIsRefusedCitingIt() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> POLICY.forSolve(3601L, new ParametresSolveur()))
                .withMessageContaining("au plus 1 h");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> POLICY.forIncremental(7200L, new ParametresSolveur()))
                .withMessageContaining("au plus 1 h");
        assertThatIllegalArgumentException().isThrownBy(() -> POLICY.checkRequested(0L));
    }

    /** The operator lowered the ceiling after the edition saved its budget: run at the ceiling, and say so. */
    @Test
    void aStoredValueAboveALoweredCeilingRunsAtTheCeilingWithAWarning() {
        SolveBudget budget = POLICY.forSolve(null, new ParametresSolveur(7200, 2400, false));

        assertThat(budget.secondsLimit()).isEqualTo(3600);
        assertThat(budget.plateauSeconds()).isEqualTo(1800);
        assertThat(budget.cappedFrom()).isEqualTo(new SolveBudget.CappedFrom(7200L, 2400L));
        assertThat(budget.warning())
                .contains("durée enregistrée")
                .contains("2 h")
                .contains("1 h")
                .contains("arrêt sur plateau")
                .contains("30 min");
    }

    @Test
    void anIncrementalKeepsItsOwnDurationAndTheEditionsPlateau() {
        assertThat(POLICY.forIncremental(null, new ParametresSolveur(2400, 60, false)))
                .isEqualTo(new SolveBudget(60L, 60L, null));
        assertThat(POLICY.forIncremental(null, new ParametresSolveur()).plateauSeconds())
                .isZero();
        assertThat(POLICY.forIncremental(120L, new ParametresSolveur()).secondsLimit())
                .isEqualTo(120);
    }

    @Test
    void theApplicationRefusesToStartUnderACeilingBelowTheDefault() {
        assertThatIllegalStateException()
                .isThrownBy(() -> SolveBudgetPolicy.checkBounds(new SolverBudgetBounds(900, 300, 600, 600)))
                .withMessageContaining("SOLVER_SECONDS_LIMIT_MAX");
        assertThatIllegalStateException()
                .isThrownBy(() -> SolveBudgetPolicy.checkBounds(new SolverBudgetBounds(900, 300, 3600, 200)))
                .withMessageContaining("SOLVER_UNIMPROVED_SECONDS_LIMIT_MAX");
        assertThatCode(() -> SolveBudgetPolicy.checkBounds(new SolverBudgetBounds(900, 300, 900, 300)))
                .doesNotThrowAnyException();
    }

    @Test
    void theUnimprovedCeilingDefaultsToTheDurationCeiling() {
        SolveBudgetPolicy policy = new SolveBudgetPolicy(900, 300, 7200, java.util.Optional.empty());

        assertThat(policy.bounds()).isEqualTo(new SolverBudgetBounds(900, 300, 7200, 7200));
    }
}
