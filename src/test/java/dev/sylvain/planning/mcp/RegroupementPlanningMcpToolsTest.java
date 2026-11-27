package dev.sylvain.planning.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import dev.sylvain.planning.mcp.EchangeMcpTools.DemandeView;
import dev.sylvain.planning.mcp.PlanningMcpTools.AffectationView;
import dev.sylvain.planning.mcp.SolveurMcpTools.JobMcpView;
import dev.sylvain.planning.mcp.VerrouillageMcpTools.VerrouillageView;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.EditionContext;
import dev.sylvain.planning.service.espace.DemandeEchangeService;
import dev.sylvain.planning.service.espace.DemandeEchangeService.NouvelleDemande;
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
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The tools 4.0 groups for the plan, the solve, the rules and the swaps (ADR
 * 0127), each called once as it should be and once as it must be refused, on
 * the plan a one-second solve of the shipped scenario leaves.
 */
@QuarkusTest
class RegroupementPlanningMcpToolsTest {

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
    ContrainteMcpTools contrainteTools;

    @Inject
    EchangeMcpTools echangeTools;

    @Inject
    VerrouillageMcpTools verrouillageTools;

    @Inject
    SolverJobService solverJobService;

    @Inject
    DemandeEchangeService demandes;

    @Inject
    EditionContext editionContext;

    /** The solve {@link #firstFilledSeat} launched. */
    private String dernierCalcul;

    @AfterEach
    void clearEdition() {
        awaitSolverIdle();
        scenarioTools.resetData("E1");
    }

    @Test
    void aSolveIsLaunchedFollowedThenForgotten() {
        AffectationView poste = firstFilledSeat();
        String jobId = dernierCalcul;

        var calcul = solveurTools.consultCalcul(jobId, true);
        assertThat(calcul.calcul().id()).isEqualTo(jobId);
        assertThat(calcul.historique()).extracting(JobMcpView::id).contains(jobId);
        assertThat(solveurTools.consultCalcul(null, null).historique()).isNull();

        JobMcpView correction = solveurTools.startCorrection(null, null, List.of(poste.standId()), 1L, null, "E1");
        assertThat(awaitFinished(correction.id()).status()).isIn(ETATS_TERMINAUX);
        assertThat(solveurTools.deleteCalcul(correction.id()).supprime()).isTrue();

        refused(() -> solveurTools.consultCalcul("job-inconnu", null), BusinessError.NotFound.class, "job-inconnu");
        refused(() -> solveurTools.stopCalcul("job-inconnu"), BusinessError.NotFound.class, "job-inconnu");
        refused(() -> solveurTools.deleteCalcul("job-inconnu"), BusinessError.NotFound.class, "job-inconnu");
        refused(
                () -> solveurTools.startCorrection(null, List.of("14/07"), null, 1L, null, "E1"),
                BusinessError.Invalid.class,
                "14/07");
    }

    @Test
    void theDiagnosticCarriesTheHardDetailOnlyWhenAsked() {
        firstFilledSeat();

        assertThat(solveurTools.diagnosePlan(null, "E1").ecartsDurs()).isNull();
        assertThat(solveurTools.diagnosePlan(true, "E1").ecartsDurs())
                .allSatisfy(ecart -> assertThat(ecart.nombreCorrespondances()).isPositive());
    }

    @Test
    void aSeatIsReadAnalysedSimulatedThenHandedOver() {
        AffectationView poste = firstFilledSeat();

        assertThat(planningTools
                        .consultPlanning(null, null, null, null, null, null, "E1")
                        .etat()
                        .resolu())
                .isTrue();
        assertThat(planningTools
                        .consultPlanning("affectations", null, null, poste.animateurId(), null, null, "E1")
                        .affectations()
                        .affectations())
                .extracting(AffectationView::posteId)
                .contains(poste.posteId());
        assertThat(planningTools.analyzeAffectation(poste.posteId(), "E1").animateurId())
                .isEqualTo(poste.animateurId());
        assertThat(planningTools
                        .simulateAffectation(poste.posteId(), "candidats", null, null, 3, null, "E1")
                        .candidats()
                        .posteId())
                .isEqualTo(poste.posteId());

        var vide = planningTools.modifyAffectation(poste.posteId(), "affecter", null, null, null, "E1");
        assertThat(vide.affectation().animateurPrecedentId()).isEqualTo(poste.animateurId());
        assertThat(vide.deplacement()).isNull();
        assertThat(solverJobService.findActive()).isEmpty();

        refused(
                () -> planningTools.consultPlanning("tout", null, null, null, null, null, "E1"),
                BusinessError.Invalid.class,
                "vue");
        refused(
                () -> planningTools.simulateAffectation(poste.posteId(), "affecter", null, null, null, null, "E1"),
                BusinessError.Invalid.class,
                "animateurId");
        refused(
                () -> planningTools.modifyAffectation(poste.posteId(), null, null, null, null, "E1"),
                BusinessError.Invalid.class,
                "mode");
    }

    @Test
    void aLockedSeatIsNotHandedOver() {
        AffectationView poste = firstFilledSeat();
        VerrouillageView verrou = verrouillageTools
                .lock("ANIMATEUR_CRENEAU", poste.animateurId(), null, poste.creneauId(), null, null, "E1")
                .verrouillage();

        refused(
                () -> planningTools.modifyAffectation(poste.posteId(), "affecter", null, null, null, "E1"),
                BusinessError.Invalid.class,
                "verrouillé");

        verrouillageTools.unlock(verrou.id(), "E1");
    }

    @Test
    void aRuleIsSetOneSettingAtATime() {
        String regle = contrainteTools.listContraintes("E1").stream()
                .filter(contrainte -> "MEDIUM".equals(contrainte.niveau()))
                .findFirst()
                .orElseThrow()
                .nom();
        try {
            assertThat(contrainteTools
                            .modifyContrainte(regle, null, 7, null, null, "E1")
                            .contrainte()
                            .poids())
                    .isEqualTo(7);
            assertThat(contrainteTools
                            .modifyContrainte(regle, false, null, null, null, "E1")
                            .contrainte()
                            .actif())
                    .isFalse();
        } finally {
            contrainteTools.modifyContrainte(regle, true, null, null, null, "E1");
            contrainteTools.modifyContrainte(regle, null, null, true, null, "E1");
        }

        refused(
                () -> contrainteTools.modifyContrainte(regle, true, 3, null, null, "E1"),
                BusinessError.Invalid.class,
                "Un réglage par appel");
        refused(
                () -> contrainteTools.modifyContrainte("regleInconnue", true, null, null, null, "E1"),
                BusinessError.NotFound.class,
                "regleInconnue");
    }

    @Test
    void aSwapRequestIsDecidedOnce() {
        AffectationView poste = firstFilledSeat();
        String collegue = planningTools.listAffectations(null, null, null, null, null, "E1").affectations().stream()
                .map(AffectationView::animateurId)
                .filter(id -> id != null && !id.equals(poste.animateurId()))
                .findFirst()
                .orElseThrow();
        boolean ouverte = echangeTools.getFoireEchanges(null, null, null, "E1").ouverte();
        try {
            assertThat(echangeTools.modifyFoireEchanges(true, null, null, "E1").ouverte())
                    .isTrue();
            String id = editionContext
                    .executeIn(
                            "E1",
                            () -> demandes.submit(
                                    poste.animateurId(),
                                    List.of(new NouvelleDemande(
                                            poste.creneauId(), poste.standId(), collegue, null, null, null))))
                    .getFirst()
                    .getId();

            DemandeView refusee = echangeTools.decideDemandeEchange(id, "refuser", null, "E1");
            assertThat(refusee.statut()).isEqualTo("REFUSEE");

            refused(
                    () -> echangeTools.decideDemandeEchange(id, "accepter", null, "E1"),
                    BusinessError.Invalid.class,
                    "plus en attente");
            refused(
                    () -> echangeTools.decideDemandeEchange(id, "annuler", null, "E1"),
                    BusinessError.Invalid.class,
                    "decision");
            refused(
                    () -> echangeTools.modifyFoireEchanges(true, "14/07", null, "E1"),
                    BusinessError.Invalid.class,
                    "14/07");
        } finally {
            echangeTools.modifyFoireEchanges(ouverte, null, null, "E1");
        }
    }

    private static void refused(ThrowingCallable call, Class<? extends BusinessError> cause, String phrase) {
        assertThatThrownBy(call)
                .isInstanceOf(ToolCallException.class)
                .hasCauseInstanceOf(cause)
                .hasMessageContaining(phrase);
    }

    private AffectationView firstFilledSeat() {
        awaitSolverIdle();
        scenarioTools.resetData("E1");
        scenarioTools.importScenario("scenario.yml", "E1");
        JobMcpView job = solveurTools.startCalcul(1L, null, null, null, "E1");
        dernierCalcul = job.id();
        assertThat(awaitFinished(job.id()).status()).isEqualTo(JobStatus.COMPLETED.name());
        return planningTools.listAffectations(null, null, null, null, null, "E1").affectations().stream()
                .filter(vue -> vue.animateurId() != null)
                .findFirst()
                .orElseThrow(() -> new AssertionError("le solve n'a pourvu aucun poste"));
    }

    private JobMcpView awaitFinished(String jobId) {
        return await().alias("Job " + jobId + " toujours en cours")
                .atMost(POLL_TIMEOUT)
                .pollInterval(POLL_INTERVAL)
                .until(
                        () -> solveurTools.consultCalcul(jobId, null).calcul(),
                        job -> job != null && ETATS_TERMINAUX.contains(job.status()));
    }

    private void awaitSolverIdle() {
        await().alias("Solveur toujours occupé")
                .atMost(POLL_TIMEOUT)
                .pollInterval(POLL_INTERVAL)
                .until(() -> solverJobService.findActive().isEmpty());
    }
}
