package dev.sylvain.planning.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import dev.sylvain.planning.mcp.InstantaneMcpTools.InstantaneDetailView;
import dev.sylvain.planning.mcp.InstantaneMcpTools.InstantaneView;
import dev.sylvain.planning.mcp.PlanningMcpTools.AffectationView;
import dev.sylvain.planning.mcp.SolveurMcpTools.JobMcpView;
import dev.sylvain.planning.mcp.VerrouillageMcpTools.VerrouillageView;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.solve.SolverJobService;
import dev.sylvain.planning.service.solve.SolverJobService.JobStatus;
import io.quarkiverse.mcp.server.ToolCallException;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The loop MCP could not run before: solve, capture, freeze what is good,
 * compare, put the previous plan back — end to end, over the tools only,
 * against the real database.
 *
 * <p>Driving it through the tools rather than through {@code /api} is the
 * point: what has to hold is that an assistant holding nothing but the MCP
 * surface can complete the loop.</p>
 */
@QuarkusTest
class PlanificationMcpToolsTest {

    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(250);

    /** PENDING and QUEUED are not the only non-terminal states: a job also sits PENDING while it starts. */
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
    VerrouillageMcpTools verrouillageTools;

    @Inject
    InstantaneMcpTools instantaneTools;

    @Inject
    SolverJobService solverJobService;

    /**
     * Leaves the edition empty. These tests persist a solved plan, and a
     * persisted seat holds its créneau: a later test class deleting a créneau
     * of its own would hit the foreign key and fail for a reason that has
     * nothing to do with it.
     */
    @AfterEach
    void clearEdition() {
        awaitSolverIdle();
        scenarioTools.resetData(null);
    }

    @Test
    void captureCompareThenRestoreAPlanning() {
        InstantaneView capture = solveAndCapture();
        assertThat(capture.automatique()).isFalse();
        assertThat(instantaneTools.listSnapshots(null))
                .extracting(InstantaneView::id)
                .contains(capture.id());

        var comparaison = instantaneTools.compareSnapshots(String.valueOf(capture.id()), "courant", null);
        assertThat(comparaison.base().snapshotId()).isEqualTo(capture.id());
        assertThat(comparaison.variante().snapshotId()).isNull();
        assertThat(comparaison.editionsDifferentes()).isFalse();

        var restauration = instantaneTools.restoreSnapshot(capture.id(), null, null);
        assertThat(restauration.affectationsRestaurees()).isEqualTo(capture.nombreAffectations());

        assertThat(instantaneTools.deleteSnapshot(capture.id(), null).supprime())
                .isTrue();
    }

    @Test
    void readingASnapshotReturnsOnlyIds() {
        InstantaneView capture = solveAndCapture();

        InstantaneDetailView detail = instantaneTools.getSnapshot(capture.id(), null, null, 5, null);

        assertThat(detail.affectations()).hasSizeLessThanOrEqualTo(5);
        assertThat(detail.affectationsTotal()).isEqualTo(capture.nombreAffectations());
        assertThat(detail.affectations())
                .allSatisfy(affectation -> assertThat(affectation.standId()).isNotBlank());

        instantaneTools.deleteSnapshot(capture.id(), null);
    }

    @Test
    void lockThenUnlockAnAnimateurOfThePlanning() {
        solve();
        String animateurId = planningTools.listAffectations(null, null, null, false, null, null).affectations().stream()
                .map(AffectationView::animateurId)
                .filter(id -> id != null)
                .findFirst()
                .orElseThrow(() -> new AssertionError("le solve n'a pourvu aucun poste"));

        VerrouillageView pose = verrouillageTools
                .lock("ANIMATEUR", animateurId, null, null, null, "vérifié avec l'équipe", null)
                .verrouillage();

        assertThat(pose.type()).isEqualTo("ANIMATEUR");
        assertThat(pose.animateurId()).isEqualTo(animateurId);
        assertThat(pose.id()).isNotBlank();
        assertThat(verrouillageTools.listVerrouillages(null))
                .extracting(VerrouillageView::id)
                .contains(pose.id());

        assertThat(verrouillageTools.unlock(pose.id(), null).supprime()).isTrue();
        assertThat(verrouillageTools.listVerrouillages(null))
                .extracting(VerrouillageView::id)
                .doesNotContain(pose.id());
    }

    @Test
    void lockingAnUnknownTargetIsRefused() {
        assertThatThrownBy(() -> verrouillageTools.lock("STAND", null, "STAND-QUI-NEXISTE-PAS", null, null, null, null))
                .isInstanceOf(ToolCallException.class)
                .hasCauseInstanceOf(BusinessError.Invalid.class)
                .hasMessageContaining("STAND-QUI-NEXISTE-PAS");
    }

    @Test
    void anUnknownLockTypeListsThePossibleTypes() {
        assertThatThrownBy(() -> verrouillageTools.lock("JOURNEE", null, null, null, "2026-08-15", null, null))
                .isInstanceOf(ToolCallException.class)
                .hasCauseInstanceOf(BusinessError.Invalid.class)
                .hasMessageContaining("ANIMATEUR_CRENEAU");
    }

    @Test
    void capturingWithoutAPersistedPlanningIsRefused() {
        awaitSolverIdle();
        scenarioTools.resetData(null);

        assertThatThrownBy(() -> instantaneTools.captureSnapshot("Sur du vide", null))
                .isInstanceOf(ToolCallException.class)
                .hasCauseInstanceOf(BusinessError.Conflict.class)
                .hasMessageContaining("résolution");
    }

    @Test
    void restoringAnUnknownSnapshotIsRefused() {
        assertThatThrownBy(() -> instantaneTools.restoreSnapshot(999_999L, null, null))
                .isInstanceOf(ToolCallException.class)
                .hasCauseInstanceOf(BusinessError.NotFound.class);
    }

    private InstantaneView solveAndCapture() {
        solve();
        InstantaneView capture = instantaneTools.captureSnapshot("Après le premier solve", null);
        assertThat(capture.nombreAffectations()).isPositive();
        return capture;
    }

    /** Loads the sample scenario and solves it once, so a plan is persisted. */
    private void solve() {
        awaitSolverIdle();
        scenarioTools.resetData(null);
        scenarioTools.importScenario("scenario.yml", null);
        JobMcpView job = solveurTools.startSolver(1L, null, null, null);
        assertThat(awaitFinished(job.id()).status()).isEqualTo("COMPLETED");
        assertThat(planningTools.planningState(null).affectationsPersistees()).isPositive();
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
