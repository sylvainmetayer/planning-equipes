package dev.sylvain.planning.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import dev.sylvain.planning.mcp.InstantaneMcpTools.InstantaneDetailView;
import dev.sylvain.planning.mcp.InstantaneMcpTools.InstantaneView;
import dev.sylvain.planning.mcp.PlanningMcpTools.AffectationView;
import dev.sylvain.planning.mcp.SolveurMcpTools.JobView;
import dev.sylvain.planning.mcp.VerrouillageMcpTools.VerrouillageView;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.SolverJobService;
import dev.sylvain.planning.service.SolverJobService.JobStatus;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

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

    private static final int MAX_POLLS = 120;
    private static final long POLL_INTERVAL_MS = 250;

    /** PENDING and QUEUED are not the only non-terminal states: a job also sits PENDING while it starts. */
    private static final Set<String> ETATS_TERMINAUX = Stream.of(JobStatus.COMPLETED, JobStatus.FAILED,
            JobStatus.CANCELLED, JobStatus.INTERROMPU).map(Enum::name).collect(Collectors.toSet());

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
    void clearEdition() throws InterruptedException {
        awaitSolverIdle();
        scenarioTools.reinitialiser_donnees(null);
    }

    @Test
    void capturerComparerPuisRestaurerUnPlanning() throws InterruptedException {
        InstantaneView capture = solveAndCapture();
        assertThat(capture.automatique()).isFalse();
        assertThat(instantaneTools.lister_instantanes(null))
                .extracting(InstantaneView::id)
                .contains(capture.id());

        var comparaison = instantaneTools.comparer_instantanes(String.valueOf(capture.id()), "courant", null);
        assertThat(comparaison.base().snapshotId()).isEqualTo(capture.id());
        assertThat(comparaison.variante().snapshotId()).isNull();
        assertThat(comparaison.editionsDifferentes()).isFalse();

        var restauration = instantaneTools.restaurer_instantane(capture.id(), null);
        assertThat(restauration.affectationsRestaurees()).isEqualTo(capture.nombreAffectations());

        assertThat(instantaneTools.supprimer_instantane(capture.id(), null).supprime()).isTrue();
    }

    @Test
    void consulterUnInstantaneNeRenvoieQueDesIdentifiants() throws InterruptedException {
        InstantaneView capture = solveAndCapture();

        InstantaneDetailView detail = instantaneTools.consulter_instantane(capture.id(), null, null, 5, null);

        assertThat(detail.affectations()).hasSizeLessThanOrEqualTo(5);
        assertThat(detail.affectationsTotal()).isEqualTo(capture.nombreAffectations());
        assertThat(detail.affectations()).allSatisfy(affectation ->
                assertThat(affectation.standId()).isNotBlank());

        instantaneTools.supprimer_instantane(capture.id(), null);
    }

    @Test
    void verrouillerPuisDeverrouillerUnAnimateurDuPlanning() throws InterruptedException {
        solve();
        String animateurId = planningTools.lister_affectations(null, null, null, false, null).stream()
                .map(AffectationView::animateurId)
                .filter(id -> id != null)
                .findFirst()
                .orElseThrow(() -> new AssertionError("le solve n'a pourvu aucun poste"));

        VerrouillageView pose = verrouillageTools.verrouiller("ANIMATEUR", animateurId, null, null, null,
                "vérifié avec l'équipe", null);

        assertThat(pose.type()).isEqualTo("ANIMATEUR");
        assertThat(pose.animateurId()).isEqualTo(animateurId);
        assertThat(pose.id()).isNotBlank();
        assertThat(verrouillageTools.lister_verrouillages(null))
                .extracting(VerrouillageView::id)
                .contains(pose.id());

        assertThat(verrouillageTools.deverrouiller(pose.id(), null).supprime()).isTrue();
        assertThat(verrouillageTools.lister_verrouillages(null))
                .extracting(VerrouillageView::id)
                .doesNotContain(pose.id());
    }

    @Test
    void verrouillerUneCibleInconnueEstRefuse() {
        assertThatThrownBy(() -> verrouillageTools.verrouiller("STAND", null, "STAND-QUI-NEXISTE-PAS", null, null,
                null, null))
                .isInstanceOf(BusinessError.Invalid.class)
                .hasMessageContaining("STAND-QUI-NEXISTE-PAS");
    }

    @Test
    void unTypeDeVerrouillageInconnuEnumereLesTypesPossibles() {
        assertThatThrownBy(() -> verrouillageTools.verrouiller("JOURNEE", null, null, null, "2026-08-15", null, null))
                .isInstanceOf(BusinessError.Invalid.class)
                .hasMessageContaining("ANIMATEUR_CRENEAU");
    }

    @Test
    void capturerSansPlanningPersisteEstRefuse() throws InterruptedException {
        awaitSolverIdle();
        scenarioTools.reinitialiser_donnees(null);

        assertThatThrownBy(() -> instantaneTools.capturer_instantane("Sur du vide", null))
                .isInstanceOf(BusinessError.Conflict.class)
                .hasMessageContaining("résolution");
    }

    @Test
    void restaurerUnInstantaneInconnuEstRefuse() {
        assertThatThrownBy(() -> instantaneTools.restaurer_instantane(999_999L, null))
                .isInstanceOf(BusinessError.NotFound.class);
    }

    private InstantaneView solveAndCapture() throws InterruptedException {
        solve();
        InstantaneView capture = instantaneTools.capturer_instantane("Après le premier solve", null);
        assertThat(capture.nombreAffectations()).isPositive();
        return capture;
    }

    /** Loads the sample scenario and solves it once, so a plan is persisted. */
    private void solve() throws InterruptedException {
        awaitSolverIdle();
        scenarioTools.reinitialiser_donnees(null);
        scenarioTools.importer_scenario("scenario.yml", null);
        JobView job = solveurTools.lancer_solveur(1L, null, null);
        assertThat(awaitFinished(job.id()).status()).isEqualTo("COMPLETED");
        assertThat(planningTools.etat_planning(null).affectationsPersistees()).isPositive();
    }

    private JobView awaitFinished(String jobId) throws InterruptedException {
        for (int essai = 0; essai < MAX_POLLS; essai++) {
            JobView job = solveurTools.statut_solveur(jobId);
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
