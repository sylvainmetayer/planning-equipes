package dev.sylvain.planning.service.solve;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.service.EditionContext;
import dev.sylvain.planning.service.solve.SolverJobRepository.LigneJob;
import dev.sylvain.planning.service.solve.SolverJobService.JobStatus;
import dev.sylvain.planning.service.solve.SolverJobService.JobType;
import dev.sylvain.planning.service.solve.SolverJobService.SolverJob;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The solver queue surviving a restart.
 *
 * <p>A restart cannot be staged over HTTP, so these tests write the rows a
 * stopped server would have left in {@code solver_job} and then call
 * {@link SolverJobService#restaurer()} — which is exactly what the
 * {@code StartupEvent} observer does when the application boots (it is disabled
 * under {@code %test}, or a queue left by one test run would start a real solve
 * as the next one boots).</p>
 *
 * <p>Two properties matter. A queued job must come back and <b>actually run</b>,
 * building its problem from the referential of the restart — which is the same
 * contract it already had in the queue, and the reason storing the intention is
 * enough. And a job that was <em>holding</em> the solver must come back
 * terminal: nothing will finish it, so leaving it {@code RUNNING} would hold
 * the global solver lock forever.</p>
 */
@QuarkusTest
class SolverJobRepriseTest {

    private static final int MAX_POLLS = 240;
    private static final long POLL_INTERVAL_MS = 250;

    @Inject
    SolverJobService jobService;

    @Inject
    SolverJobRepository jobRepository;

    @Inject
    EditionContext editionContext;

    @Inject
    DataSource dataSource;

    @BeforeEach
    @AfterEach
    void solveurLibreEtTableVide() throws InterruptedException {
        clearQueue();
        attendreSolveurLibre();
        clearTable();
    }

    @Test
    void uneFileEcriteAvantLeRedemarrageRepartEtVaAuBout() throws InterruptedException {
        planImporte();
        // What a server stopped mid-queue leaves behind: two runs planned, in
        // order. Different types on purpose — the same type twice on the same
        // edition is what the double-click guard refuses at submit time.
        String solveId = writeRow(JobType.SOLVE, JobStatus.QUEUED, true, null);
        String incrementalId = writeRow(JobType.SOLVE_INCREMENTAL, JobStatus.QUEUED, true, null);

        assertThat(jobService.restaurer()).isEqualTo(2);

        // Nobody clicked anything: the first one took the solver by itself, and
        // it really solved — only possible if the task was rebuilt, not just
        // the row.
        assertThat(pollUntilFinished(solveId).getString("status")).isEqualTo("COMPLETED");
        JsonPath incremental = pollUntilFinished(incrementalId);
        assertThat(incremental.getString("status")).isEqualTo("COMPLETED");
        assertThat(incremental.getString("type")).isEqualTo("SOLVE_INCREMENTAL");
        // Built when it started, after the restored solve persisted its plan:
        // an incremental re-solve fails outright without one.
        assertThat(incremental.getInt("result.statistiques.postesFiges")).isPositive();
    }

    @Test
    void unSolveQuiTenaitLeSolveurRevientInterrompu() {
        String id = writeRow(JobType.SOLVE, JobStatus.RUNNING, true, null);

        assertThat(jobService.restaurer()).isZero();

        SolverJob job = jobService.find(id).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(JobStatus.INTERROMPU);
        assertThat(job.isFinished()).isTrue();
        assertThat(job.getError()).contains("redémarrage");
        // The point of the terminal state: the global lock is free again.
        assertThat(jobService.findActive()).isEmpty();
        assertThat(jobService.fileAttente()).isEmpty();
    }

    @Test
    void unJobQuiNeSaitPasReconstruireSonProblemeNeRedemarreJamais() {
        // A solve whose problem arrived in the request body: that body is not
        // stored, so replaying the row would solve something else entirely.
        String id = writeRow(JobType.SOLVE, JobStatus.QUEUED, false, null);

        assertThat(jobService.restaurer()).isZero();

        assertThat(jobService.find(id).orElseThrow().getStatus()).isEqualTo(JobStatus.INTERROMPU);
        assertThat(jobService.fileAttente()).isEmpty();
    }

    @Test
    void lePerimetreDuneReplanificationSurvitAuRedemarrage() {
        ReplanificationScope scope =
                new ReplanificationScope(Set.of("ANIM-1"), Set.of(LocalDate.of(2026, 8, 21)), Set.of("STAND-1"));
        String id = writeRow(JobType.SOLVE_INCREMENTAL, JobStatus.QUEUED, true, scope);

        LigneJob relue = jobRepository.list().stream()
                .filter(ligne -> ligne.id().equals(id))
                .findFirst()
                .orElseThrow();

        // Rebuilt identically: a replanning replayed with an empty perimeter
        // would silently re-optimise less than the operator asked for.
        assertThat(relue.scope()).isEqualTo(scope);
    }

    @Test
    void laRepriseAuDemarrageEstDesactivableParConfiguration() {
        // The %test profile switches it off — without that, a job left queued
        // by one test run would start a real solve as the next one boots. This
        // guard is what makes the whole suite deterministic, so it is worth an
        // assertion of its own.
        String id = writeRow(JobType.SOLVE, JobStatus.QUEUED, true, null);

        jobService.reprendreAuDemarrage(new StartupEvent());

        assertThat(jobService.find(id)).isEmpty();
        assertThat(jobService.fileAttente()).isEmpty();
        assertThat(statut(id)).isEqualTo(JobStatus.QUEUED);
    }

    @Test
    void unJobOublieDisparaitAussiDeLaBase() throws InterruptedException {
        planImporte();
        String id = given().when()
                .post("/api/solve/async/reference-data?seconds=1")
                .then()
                .statusCode(202)
                .extract()
                .path("id");
        assertThat(pollUntilFinished(id).getString("status")).isEqualTo("COMPLETED");
        // Written by the real code path, not by this test's helper — and awaited
        // rather than read straight: the REST status comes from the in-memory
        // job, which `finishAndChain` marks terminal *before* it stores the row,
        // and `find` reads it without the lock. Seeing COMPLETED over HTTP
        // therefore promises nothing about the row yet.
        assertThat(pollUntilStatut(id, JobStatus.COMPLETED)).isEqualTo(JobStatus.COMPLETED);

        given().when().delete("/api/jobs/" + id).then().statusCode(204);

        assertThat(jobRepository.list().stream().map(LigneJob::id)).doesNotContain(id);
    }

    /**
     * The queued job's starting point has to survive the restart with it (ADR
     * 0024): a file replayed after a reboot must start the way it was asked to,
     * and a cold start silently coming back warm would quietly rewrite a plan
     * the operator had decided to throw away.
     */
    @Test
    void theRequestedReamorcageSurvivesARestart() {
        for (Reamorcage demande : new Reamorcage[] {Reamorcage.AUCUN, Reamorcage.PLAN_COURANT, null}) {
            String id = UUID.randomUUID().toString();
            jobRepository.save(new LigneJob(
                    id,
                    editionContext.editionIdCourant(),
                    "Édition de test",
                    JobType.SOLVE,
                    JobStatus.PENDING,
                    1L,
                    null,
                    demande,
                    true,
                    null,
                    Instant.now(),
                    null,
                    null));
            assertThat(jobRepository.list().stream().filter(ligne -> ligne.id().equals(id)))
                    .as("reamorcage %s read back from the database", demande)
                    .singleElement()
                    .extracting(LigneJob::reamorcage)
                    .isEqualTo(demande);
            jobRepository.delete(id);
        }
    }

    /* ------------------------------- Helpers ------------------------------- */

    /** Writes the row a server stopped in that state would have left behind. */
    private String writeRow(JobType type, JobStatus statut, boolean rejouable, ReplanificationScope scope) {
        String id = UUID.randomUUID().toString();
        Instant maintenant = Instant.now();
        jobRepository.save(new LigneJob(
                id,
                editionContext.editionIdCourant(),
                "Édition de test",
                type,
                statut,
                1L,
                scope,
                null,
                rejouable,
                null,
                maintenant,
                statut == JobStatus.RUNNING ? maintenant : null,
                null));
        return id;
    }

    private JobStatus statut(String id) {
        return jobRepository.list().stream()
                .filter(ligne -> ligne.id().equals(id))
                .map(LigneJob::statut)
                .findFirst()
                .orElseThrow();
    }

    private void clearTable() {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM solver_job");
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to clear solver_job", e);
        }
    }

    private void planImporte() {
        given().when().post("/api/planning/reset").then().statusCode(200);
        given().when()
                .post("/api/reference-data/import-scenario?name=scenario.yml")
                .then()
                .statusCode(200);
    }

    private void clearQueue() {
        List<String> ids = given().when()
                .get("/api/jobs/file")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getList("id");
        for (String id : ids) {
            given().when().delete("/api/jobs/" + id);
        }
    }

    private void attendreSolveurLibre() throws InterruptedException {
        for (int i = 0; i < MAX_POLLS; i++) {
            if (given().when().get("/api/jobs/active").then().extract().statusCode() == 204) {
                return;
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        throw new AssertionError("Solver still busy");
    }

    /** The persisted status, awaited on the same budget as {@link #pollUntilFinished}. */
    private JobStatus pollUntilStatut(String jobId, JobStatus attendu) throws InterruptedException {
        JobStatus vu = null;
        for (int i = 0; i < MAX_POLLS; i++) {
            vu = statut(jobId);
            if (vu == attendu) {
                return vu;
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        return vu;
    }

    private JsonPath pollUntilFinished(String jobId) throws InterruptedException {
        for (int i = 0; i < MAX_POLLS; i++) {
            JsonPath job = given().when()
                    .get("/api/jobs/" + jobId)
                    .then()
                    .statusCode(200)
                    .extract()
                    .jsonPath();
            if (List.of("COMPLETED", "FAILED", "CANCELLED", "INTERROMPU").contains(job.getString("status"))) {
                return job;
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        throw new AssertionError("Job " + jobId + " did not finish in time");
    }
}
