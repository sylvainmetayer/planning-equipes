package dev.sylvain.planning.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sylvain.planning.mcp.PlanningMcpTools.AffectationView;
import dev.sylvain.planning.mcp.SolveurMcpTools.JobMcpView;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.solve.SolverJobService;
import dev.sylvain.planning.service.solve.SolverJobService.JobStatus;
import io.quarkiverse.mcp.server.ToolCallException;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
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

    private static final int MAX_POLLS = 160;
    private static final long POLL_INTERVAL_MS = 250;

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
    void clearEdition() throws InterruptedException {
        awaitSolverIdle();
        scenarioTools.reinitialiser_donnees(null);
    }

    @Test
    void uneResolutionPeutAttendreSonTourAuLieuDEchouer() throws InterruptedException {
        loadScenario();
        JobMcpView premier = solveurTools.lancer_solveur(4L, null, null, null);

        JobMcpView enFile = solveurTools.lancer_solveur(1L, true, null, null);

        assertThat(enFile.status()).isEqualTo(JobStatus.QUEUED.name());
        assertThat(enFile.id()).isNotEqualTo(premier.id());
        assertThat(awaitFinished(enFile.id()).status()).isEqualTo(JobStatus.COMPLETED.name());
    }

    @Test
    void sansMiseEnFileUneResolutionConcurrenteEstRefusee() throws InterruptedException {
        loadScenario();
        JobMcpView premier = solveurTools.lancer_solveur(4L, null, null, null);

        assertThatThrownBy(() -> solveurTools.lancer_solveur(1L, false, null, null))
                .isInstanceOf(ToolCallException.class)
                .hasCauseInstanceOf(BusinessError.Conflict.class)
                .hasMessageContaining(premier.id())
                .hasMessageContaining("enFile");

        solveurTools.arreter_solveur(premier.id());
    }

    @Test
    void laResolutionIncrementaleRepartDuPlanningPersiste() throws InterruptedException {
        loadScenario();
        assertThat(awaitFinished(solveurTools
                                .lancer_solveur(1L, null, null, null)
                                .id())
                        .status())
                .isEqualTo(JobStatus.COMPLETED.name());
        int affectations = planningTools.etat_planning(null).affectationsPersistees();
        String animateurId =
                planningTools.lister_affectations(null, null, null, false, null, null).affectations().stream()
                        .map(AffectationView::animateurId)
                        .filter(id -> id != null)
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("le solve n'a pourvu aucun poste"));

        JobMcpView incremental = solveurTools.resoudre_incremental(List.of(animateurId), null, null, 1L, null, null);

        assertThat(incremental.type()).isEqualTo("SOLVE_INCREMENTAL");
        assertThat(awaitFinished(incremental.id()).status()).isEqualTo(JobStatus.COMPLETED.name());
        assertThat(planningTools.etat_planning(null).affectationsPersistees()).isEqualTo(affectations);
    }

    @Test
    void unPerimetreSansCibleResoutCeQueLesChangementsOntInvalide() throws InterruptedException {
        loadScenario();
        assertThat(awaitFinished(solveurTools
                                .lancer_solveur(1L, null, null, null)
                                .id())
                        .status())
                .isEqualTo(JobStatus.COMPLETED.name());

        JobMcpView incremental = solveurTools.resoudre_incremental(null, null, null, 1L, null, null);

        assertThat(awaitFinished(incremental.id()).status()).isEqualTo(JobStatus.COMPLETED.name());
    }

    @Test
    void unJourMalFormeDansLePerimetreEstRefuseAvantToutLancement() {
        assertThatThrownBy(() -> solveurTools.resoudre_incremental(null, List.of("15/08/2026"), null, 1L, null, null))
                .isInstanceOf(ToolCallException.class)
                .hasCauseInstanceOf(BusinessError.Invalid.class)
                .hasMessageContaining("AAAA-MM-JJ");
        assertThat(solverJobService.findActive()).isEmpty();
    }

    private void loadScenario() throws InterruptedException {
        awaitSolverIdle();
        scenarioTools.reinitialiser_donnees(null);
        scenarioTools.importer_scenario("scenario.yml", null);
    }

    private JobMcpView awaitFinished(String jobId) throws InterruptedException {
        for (int essai = 0; essai < MAX_POLLS; essai++) {
            JobMcpView job = solveurTools.statut_solveur(jobId);
            if (job != null && ETATS_TERMINAUX.contains(job.status())) {
                return job;
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        throw new AssertionError("Job " + jobId + " toujours en cours");
    }

    private void awaitSolverIdle() throws InterruptedException {
        for (int essai = 0; essai < MAX_POLLS; essai++) {
            if (solverJobService.findActive().isEmpty()) {
                return;
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        throw new AssertionError("Solveur toujours occupé");
    }
}
