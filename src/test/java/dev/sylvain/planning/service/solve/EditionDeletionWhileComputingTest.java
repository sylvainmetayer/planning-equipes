package dev.sylvain.planning.service.solve;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import dev.sylvain.planning.domain.Edition;
import dev.sylvain.planning.domain.ParametresSolveur;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.EditionContext;
import dev.sylvain.planning.service.edition.EditionService;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.solve.SolverJobRepository.LigneJob;
import dev.sylvain.planning.service.solve.SolverJobService.JobStatus;
import dev.sylvain.planning.service.solve.SolverJobService.JobType;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Deleting an edition a computation still works on is refused in {@code 409}:
 * a solver job of that edition, running or queued — a queue replayed at
 * startup included. The guard lives in {@link EditionService#delete}, so the
 * REST route and the {@code supprimer_edition} MCP tool are both covered here.
 *
 * <p>A solve is held running by its edition's plateau: one minute without
 * improvement, where the test profile's two seconds could end it before the
 * assertions are made.</p>
 */
@QuarkusTest
class EditionDeletionWhileComputingTest {

    @Inject
    EditionService editions;

    @Inject
    EditionContext editionContext;

    @Inject
    ReferenceDataService referenceData;

    @Inject
    SolverJobService solverJobs;

    @Inject
    SolverJobRepository jobRepository;

    private final List<String> jobsLances = new ArrayList<>();

    private final List<String> editionsCreees = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        // Only what still runs or waits: cancelling a finished job writes its
        // row again, and the edition it belonged to may be gone.
        jobsLances.stream()
                .filter(id ->
                        solverJobs.find(id).filter(job -> !job.isFinished()).isPresent())
                .forEach(solverJobs::cancel);
        waitForFreeSolver();
        for (String edition : editionsCreees) {
            try {
                editions.delete(edition);
            } catch (BusinessError _) {
                // Already deleted by the test itself.
            }
        }
    }

    @Test
    void aRunningSolveOfTheEditionRefusesItsDeletion() {
        String edition = editionThatSolvesLong("Édition en calcul");
        String job = launchLongSolve(edition);

        assertThatThrownBy(() -> editions.delete(edition))
                .isInstanceOf(BusinessError.Conflict.class)
                .hasMessageContaining("en cours");

        solverJobs.cancel(job);
        waitForFreeSolver();
        editions.delete(edition);
        assertThat(editions.listEditions()).noneMatch(e -> e.getId().equals(edition));
    }

    @Test
    void aQueuedSolveOfTheEditionRefusesItsDeletionAndAnotherEditionsSolveDoesNot() {
        String occupee = editionThatSolvesLong("Édition qui tient le solveur");
        String enFile = emptyEdition("Édition en file");
        launchLongSolve(occupee);
        String attente = editionContext.executeIn(
                enFile, () -> solverJobs.submitSolveFromReferenceData(1L, true).getId());
        jobsLances.add(attente);
        assertThat(solverJobs.find(attente).orElseThrow().getStatus()).isEqualTo(JobStatus.QUEUED);

        assertThatThrownBy(() -> editions.delete(enFile))
                .isInstanceOf(BusinessError.Conflict.class)
                .hasMessageContaining("file");

        // Out of the queue, nothing holds that edition any more: the solve
        // running next door is another edition's, and refuses nothing here.
        solverJobs.cancel(attente);
        editions.delete(enFile);
        assertThat(editions.listEditions()).noneMatch(e -> e.getId().equals(enFile));
    }

    @Test
    void aQueueReplayedAtStartupRefusesTheDeletionToo() {
        String occupee = editionThatSolvesLong("Édition qui reprend le solveur au redémarrage");
        String rejouee = emptyEdition("Édition rejouée");
        waitForFreeSolver();
        // The rows a server stopped with two runs planned leaves behind,
        // replayed as the startup observer does: the first takes the solver
        // again, the second waits behind it.
        String premier = writeQueuedRow(occupee);
        String second = writeQueuedRow(rejouee);
        solverJobs.restaurer();
        assertThat(solverJobs.find(second).orElseThrow().getStatus()).isEqualTo(JobStatus.QUEUED);

        assertThatThrownBy(() -> editions.delete(rejouee))
                .isInstanceOf(BusinessError.Conflict.class)
                .hasMessageContaining("file");
        assertThatThrownBy(() -> editions.delete(occupee))
                .isInstanceOf(BusinessError.Conflict.class)
                .hasMessageContaining("en cours");

        solverJobs.cancel(second);
        solverJobs.cancel(premier);
        editions.delete(rejouee);
        assertThat(editions.listEditions()).noneMatch(e -> e.getId().equals(rejouee));
    }

    /** The row a server stopped with this run planned leaves behind in {@code solver_job}. */
    private String writeQueuedRow(String edition) {
        String id = UUID.randomUUID().toString();
        jobRepository.save(new LigneJob(
                id,
                edition,
                "Édition rejouée",
                JobType.SOLVE,
                JobStatus.QUEUED,
                60L,
                null,
                null,
                null,
                null,
                true,
                null,
                Instant.now(),
                null,
                null));
        jobsLances.add(id);
        return id;
    }

    /**
     * An edition holding the nominal scenario, whose solves wait a minute for
     * a better plan once they have one: long enough to hold the solver
     * through the assertions, where the test profile's two seconds could end
     * it before they are made.
     */
    private String editionThatSolvesLong(String nom) {
        String edition = emptyEdition(nom);
        given().header("X-Edition-Id", edition)
                .when()
                .post("/api/reference-data/import-scenario?name=scenario.yml")
                .then()
                .statusCode(200);
        editionContext.executeIn(
                edition, () -> referenceData.updateParametresSolveur(new ParametresSolveur(60, 60, false)));
        return edition;
    }

    /** An empty edition: a job queued on it is cancelled before it ever reads anything. */
    private String emptyEdition(String nom) {
        String edition = editions.create(new Edition(null, nom, false, null)).getId();
        editionsCreees.add(edition);
        return edition;
    }

    private String launchLongSolve(String edition) {
        waitForFreeSolver();
        String job = editionContext.executeIn(
                edition,
                () -> solverJobs.submitSolveFromReferenceData(60L, false).getId());
        jobsLances.add(job);
        assertThat(solverJobs.findActive())
                .hasValueSatisfying(actif -> assertThat(actif.getId()).isEqualTo(job));
        return job;
    }

    /** Asserts nothing on purpose: called from the clean-up, it must not mask the real failure. */
    private void waitForFreeSolver() {
        try {
            await().atMost(Duration.ofSeconds(120))
                    .pollInterval(Duration.ofMillis(250))
                    .until(() -> solverJobs.findActive().isEmpty());
        } catch (ConditionTimeoutException _) {
            // A solver that never frees up shows as the refusal it causes.
        }
    }
}
