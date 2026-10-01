package dev.sylvain.planning.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import dev.sylvain.planning.mcp.PlanningMcpTools.AffectationView;
import dev.sylvain.planning.mcp.SolveurMcpTools.JobMcpView;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.solve.SolverJobService;
import dev.sylvain.planning.service.solve.SolverJobService.JobStatus;
import io.quarkiverse.mcp.server.ToolCallException;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * What the switch to the replayable submissions buys, seen from outside: a
 * solve launched over MCP can wait its turn, and a partial re-solve exists at
 * all.
 *
 * <p>Queueing is the observable proof of the change. Only a job that builds
 * its own problem may be queued, so the previous {@code submitSolve} — handed
 * a problem built on the calling thread — could not have produced a
 * {@code QUEUED} job whatever the arguments.</p>
 */
@QuarkusTest
class SolveurMcpToolsQueueTest {

    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(80);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(250);

    private static final Set<String> ETATS_TERMINAUX = Stream.of(
                    JobStatus.COMPLETED, JobStatus.FAILED, JobStatus.CANCELLED, JobStatus.INTERROMPU)
            .map(Enum::name)
            .collect(Collectors.toSet());

    @Inject
    ScenarioMcpTools scenarioTools;

    @Inject
    SolveurMcpTools solveurTools;

    @Inject
    PlanningMcpTools planningTools;

    @Inject
    SolverJobService solverJobService;

    @AfterEach
    void clearEdition() {
        awaitSolverIdle();
        scenarioTools.resetData("E1");
    }

    @Test
    void aSolveCanWaitForItsTurnInsteadOfFailing() {
        loadScenario();
        JobMcpView premier = solveurTools.startSolver(4L, null, null, "E1");

        JobMcpView enFile = solveurTools.startSolver(1L, true, null, "E1");

        assertThat(enFile.status()).isEqualTo(JobStatus.QUEUED.name());
        assertThat(enFile.id()).isNotEqualTo(premier.id());
        assertThat(awaitFinished(enFile.id()).status()).isEqualTo(JobStatus.COMPLETED.name());
    }

    @Test
    void withoutQueueingAConcurrentSolveIsRefused() {
        loadScenario();
        JobMcpView premier = solveurTools.startSolver(4L, null, null, "E1");

        assertThatThrownBy(() -> solveurTools.startSolver(1L, false, null, "E1"))
                .isInstanceOf(ToolCallException.class)
                .hasCauseInstanceOf(BusinessError.Conflict.class)
                .hasMessageContaining(premier.id())
                .hasMessageContaining("enFile");

        solveurTools.stopSolver(premier.id());
    }

    @Test
    void theIncrementalSolveStartsFromThePersistedPlanning() {
        loadScenario();
        assertThat(awaitFinished(solveurTools.startSolver(1L, null, null, "E1").id())
                        .status())
                .isEqualTo(JobStatus.COMPLETED.name());
        int affectations = planningTools.planningState("E1").affectationsPersistees();
        String animateurId = planningTools.listAffectations(null, null, null, false, null, "E1").affectations().stream()
                .map(AffectationView::animateurId)
                .filter(id -> id != null)
                .findFirst()
                .orElseThrow(() -> new AssertionError("le solve n'a pourvu aucun poste"));

        JobMcpView incremental = solveurTools.solveIncremental(List.of(animateurId), null, null, 1L, null, "E1");

        assertThat(incremental.type()).isEqualTo("SOLVE_INCREMENTAL");
        assertThat(awaitFinished(incremental.id()).status()).isEqualTo(JobStatus.COMPLETED.name());
        assertThat(planningTools.planningState("E1").affectationsPersistees()).isEqualTo(affectations);
    }

    @Test
    void aScopeWithoutTargetSolvesWhatTheChangesInvalidated() {
        loadScenario();
        assertThat(awaitFinished(solveurTools.startSolver(1L, null, null, "E1").id())
                        .status())
                .isEqualTo(JobStatus.COMPLETED.name());

        JobMcpView incremental = solveurTools.solveIncremental(null, null, null, 1L, null, "E1");

        assertThat(awaitFinished(incremental.id()).status()).isEqualTo(JobStatus.COMPLETED.name());
    }

    @Test
    void aMalformedDayInTheScopeIsRefusedBeforeAnyStart() {
        List<String> dates = List.of("15/08/2026");
        assertThatThrownBy(() -> solveurTools.solveIncremental(null, dates, null, 1L, null, "E1"))
                .isInstanceOf(ToolCallException.class)
                .hasCauseInstanceOf(BusinessError.Invalid.class)
                .hasMessageContaining("AAAA-MM-JJ");
        assertThat(solverJobService.findActive()).isEmpty();
    }

    private void loadScenario() {
        awaitSolverIdle();
        scenarioTools.resetData("E1");
        scenarioTools.importScenario("scenario.yml", "E1");
    }

    private JobMcpView awaitFinished(String jobId) {
        return await().alias("Job " + jobId + " toujours en cours")
                .atMost(POLL_TIMEOUT)
                .pollInterval(POLL_INTERVAL)
                .until(
                        () -> solveurTools.solverStatus(jobId),
                        job -> job != null && ETATS_TERMINAUX.contains(job.status()));
    }

    private void awaitSolverIdle() {
        await().alias("Solveur toujours occupé")
                .atMost(POLL_TIMEOUT)
                .pollInterval(POLL_INTERVAL)
                .until(() -> solverJobService.findActive().isEmpty());
    }
}
