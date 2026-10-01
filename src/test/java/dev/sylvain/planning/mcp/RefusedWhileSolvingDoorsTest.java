package dev.sylvain.planning.mcp;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.EditionContext;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.solve.PlanSnapshotService;
import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import dev.sylvain.planning.service.solve.RefusedWhileSolving;
import dev.sylvain.planning.service.solve.SolverJobService;
import io.quarkiverse.mcp.server.ToolCallException;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.Test;

/**
 * The guard {@link RefusedWhileSolving} carries is the service's, so both
 * doors to a guarded write meet it: the REST resource and the MCP tool, which
 * calls the service directly and never crosses JAX-RS. A solve holds the
 * edition; deleting an animateur its problem names and restoring a snapshot
 * are refused through each door, with the answer each one has always given —
 * the {@code 409} naming the job over REST, the job's refusal itself or the
 * tool's own wording of it over MCP — and nothing is written.
 */
@QuarkusTest
class RefusedWhileSolvingDoorsTest {

    private static final LocalDate JOUR = LocalDate.of(2030, 9, 17);

    private static final String ANIMATEUR = "GARDE-A1";

    @Inject
    AnimateurMcpTools animateurTools;

    @Inject
    InstantaneMcpTools snapshotTools;

    @Inject
    ReferenceDataService referenceData;

    @Inject
    PlanningPersistenceService persistence;

    @Inject
    PlanSnapshotService snapshots;

    @Inject
    SolverJobService solverJobs;

    @Inject
    EditionContext editionContext;

    @Test
    void aRunningSolveRefusesTheWriteThroughRestAndThroughMcp() {
        Creneau creneau = new Creneau(9801L, 1, JOUR, LocalTime.of(9, 0), LocalTime.of(12, 0));
        Stand stand = new Stand("GARDE-S1", "Stand GARDE-S1", Set.of(), 1, 1, false);
        Animateur animateur = new Animateur(ANIMATEUR, "Prenom", "Nom", LocalDate.of(1990, 1, 1), false);
        PosteAffectation poste = new PosteAffectation("GARDE-P1", stand, creneau);
        poste.setAnimateur(animateur);
        PlanningEvenement probleme = new PlanningEvenement(JOUR, List.of(animateur), List.of(poste));
        String edition = editionContext.editionIdCourant();
        String jobId = null;
        Long snapshotId = null;
        try {
            persistence.persist(probleme);
            waitForFreeSolver();
            snapshotId = snapshots.capture("Avant le calcul gardé", false).id();
            jobId = solverJobs.submitSolve(probleme, 60L).getId();
            String job = jobId;
            long snapshot = snapshotId;

            // REST: SolverOccupeMapper's 409, the job that holds the solver as its body.
            given().header("X-Edition-Id", edition)
                    .when()
                    .delete("/api/animateurs/" + ANIMATEUR)
                    .then()
                    .statusCode(409)
                    .body("id", equalTo(job))
                    .body("message", containsString("résolution est en cours"));
            given().header("X-Edition-Id", edition)
                    .when()
                    .post("/api/planning/snapshots/" + snapshot + "/restore")
                    .then()
                    .statusCode(409)
                    .body("id", equalTo(job));

            // MCP: the tool reaches the service without JAX-RS, and meets the same guard.
            assertThatThrownBy(() -> animateurTools.deleteAnimateur(ANIMATEUR, edition))
                    .isInstanceOfSatisfying(
                            SolverJobService.SolverBusyException.class,
                            busy -> assertThat(busy.getActiveJob().getId()).isEqualTo(job));
            assertThatThrownBy(() -> snapshotTools.restoreSnapshot(snapshot, false, edition))
                    .isInstanceOf(ToolCallException.class)
                    .hasMessageContaining("Une résolution est en cours sur cette édition (job " + job + ")");

            // Refused means untouched: the animateur is there, still in their seat.
            assertThat(referenceData.listAnimateurs()).anyMatch(a -> ANIMATEUR.equals(a.getId()));
            assertThat(persistence.loadPersistedPlanning().getPostes())
                    .filteredOn(seat -> "GARDE-P1".equals(seat.getId()))
                    .singleElement()
                    .satisfies(seat -> assertThat(seat.getAnimateur()).isNotNull());
        } finally {
            if (jobId != null) {
                solverJobs.cancel(jobId);
            }
            waitForFreeSolver();
            persistence.persist(new PlanningEvenement(JOUR, List.of(), List.of()));
            if (snapshotId != null) {
                snapshots.delete(snapshotId);
            }
            referenceData.deleteStand("GARDE-S1");
            referenceData.deleteAnimateur(ANIMATEUR);
            referenceData.deleteCreneaux(List.of(9801L));
        }
    }

    /** Asserts nothing on purpose: called in the {@code finally}, it must not mask the real failure. */
    private void waitForFreeSolver() {
        try {
            await().atMost(Duration.ofSeconds(120))
                    .pollInterval(Duration.ofMillis(500))
                    .until(() -> solverJobs.findActive().isEmpty());
        } catch (ConditionTimeoutException _) {
            // Deliberately silent: a solver that never frees up shows as the refusal it causes.
        }
    }
}
