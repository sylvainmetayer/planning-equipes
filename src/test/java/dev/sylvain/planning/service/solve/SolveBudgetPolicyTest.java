package dev.sylvain.planning.service.solve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import org.junit.jupiter.api.Test;

/** How a launch becomes a job's budget: the instance's own, under the operator's ceiling. */
class SolveBudgetPolicyTest {

    /** Default 15 min with a 5 min plateau, at most 1 h. */
    private static final SolveBudgetPolicy POLICY = new SolveBudgetPolicy(new SolverBudgetBounds(900, 300, 3600));

    @Test
    void aLaunchThatAsksForNothingRunsTheInstanceBudget() {
        SolveBudget budget = POLICY.forSolve(null);

        assertThat(budget).isEqualTo(new SolveBudget(900L, 300L));
    }

    /** « Exactly that long » — how the scenario harnesses and the test profile stay apart. */
    @Test
    void anExplicitDurationRunsItsWholeBudgetWithoutAPlateau() {
        assertThat(POLICY.forSolve(1200L).plateauSeconds()).isZero();
        assertThat(POLICY.forSolve(900L).plateauSeconds()).isEqualTo(300);
    }

    @Test
    void aDurationAskedAboveTheCeilingIsRefusedCitingIt() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> POLICY.forSolve(3601L))
                .withMessageContaining("au plus 1 h");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> POLICY.forIncremental(7200L))
                .withMessageContaining("au plus 1 h");
        assertThatIllegalArgumentException().isThrownBy(() -> POLICY.checkRequested(0L));
    }

    @Test
    void anIncrementalKeepsItsOwnShortDurationAndNoPlateau() {
        assertThat(POLICY.forIncremental(null)).isEqualTo(new SolveBudget(60L, 0L));
        assertThat(POLICY.forIncremental(120L).secondsLimit()).isEqualTo(120);
    }

    @Test
    void theApplicationRefusesToStartUnderACeilingBelowTheDefault() {
        assertThatIllegalStateException()
                .isThrownBy(() -> SolveBudgetPolicy.checkBounds(new SolverBudgetBounds(900, 300, 600)))
                .withMessageContaining("SOLVER_SECONDS_LIMIT_MAX");
        assertThatCode(() -> SolveBudgetPolicy.checkBounds(new SolverBudgetBounds(900, 300, 900)))
                .doesNotThrowAnyException();
    }

    @Test
    void theBoundsAreTheConfiguredValues() {
        SolveBudgetPolicy policy = new SolveBudgetPolicy(900, 300, 7200);

        assertThat(policy.bounds()).isEqualTo(new SolverBudgetBounds(900, 300, 7200));
    }
}
