package dev.sylvain.planning.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import dev.sylvain.planning.mcp.PlanningMcpTools.AffectationView;
import dev.sylvain.planning.mcp.PlanningMcpTools.SuggestionsView;
import dev.sylvain.planning.mcp.SolveurMcpTools.JobView;
import dev.sylvain.planning.mcp.VerrouillageMcpTools.VerrouillageView;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.SolverJobService;
import dev.sylvain.planning.service.SolverJobService.JobStatus;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

/**
 * Repairing one seat without re-solving: find the candidates, apply one, and
 * be refused on a seat the operator has frozen.
 *
 * <p>The refusal matters as much as the repair. A lock is what an assistant is
 * told to respect, and {@code affecter_poste} is the one tool here that could
 * walk over it silently.</p>
 */
@QuarkusTest
class ReparationMcpToolsTest {

    private static final int MAX_POLLS = 160;
    private static final long POLL_INTERVAL_MS = 250;

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
    SolverJobService solverJobService;

    @AfterEach
    void clearEdition() throws InterruptedException {
        awaitSolverIdle();
        scenarioTools.reinitialiser_donnees(null);
    }

    @Test
    void suggereDesCandidatsPuisEnApplique() throws InterruptedException {
        AffectationView poste = premierPostePourvu();

        SuggestionsView suggestions = planningTools.suggerer_reparations(poste.posteId(), 5, null);

        assertThat(suggestions.posteId()).isEqualTo(poste.posteId());
        assertThat(suggestions.animateurActuelId()).isEqualTo(poste.animateurId());
        assertThat(suggestions.candidatsEvalues()).isLessThanOrEqualTo(suggestions.candidatsEligibles());
        assertThat(suggestions.suggestions())
                .allSatisfy(suggestion -> assertThat(suggestion.animateurId()).isNotBlank());
    }

    @Test
    void affecterUnPosteLeFaitChangerDeMainSansRelancerDeResolution() throws InterruptedException {
        AffectationView poste = premierPostePourvu();

        var reaffectation = planningTools.affecter_poste(poste.posteId(), null, null);

        assertThat(reaffectation.animateurPrecedentId()).isEqualTo(poste.animateurId());
        assertThat(reaffectation.animateurId()).isNull();
        assertThat(planningTools.lister_affectations(null, null, null, null, null, null).affectations())
                .filteredOn(vue -> vue.posteId().equals(poste.posteId()))
                .singleElement()
                .satisfies(vue -> assertThat(vue.animateurId()).isNull());
        assertThat(solverJobService.findActive()).isEmpty();
    }

    @Test
    void affecterUnPosteVerrouilleEstRefuse() throws InterruptedException {
        AffectationView poste = premierPostePourvu();
        VerrouillageView verrou = verrouillageTools.verrouiller("ANIMATEUR", poste.animateurId(), null, null, null,
                null, null);

        assertThatThrownBy(() -> planningTools.affecter_poste(poste.posteId(), null, null))
                .isInstanceOf(BusinessError.Invalid.class)
                .hasMessageContaining("verrouillé");

        verrouillageTools.deverrouiller(verrou.id(), null);
    }

    @Test
    void suggererSurUnPosteInconnuEstRefuse() throws InterruptedException {
        premierPostePourvu();

        assertThatThrownBy(() -> planningTools.suggerer_reparations("POSTE-INCONNU", null, null))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void suggererSansPlanningPersisteLeDit() {
        scenarioTools.reinitialiser_donnees(null);

        assertThatThrownBy(() -> planningTools.suggerer_reparations("P1", null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("résolution");
    }

    private AffectationView premierPostePourvu() throws InterruptedException {
        awaitSolverIdle();
        scenarioTools.reinitialiser_donnees(null);
        scenarioTools.importer_scenario("scenario.yml", null);
        JobView job = solveurTools.lancer_solveur(1L, null, null);
        assertThat(awaitFinished(job.id()).status()).isEqualTo(JobStatus.COMPLETED.name());
        return planningTools.lister_affectations(null, null, null, null, null, null).affectations().stream()
                .filter(vue -> vue.animateurId() != null)
                .findFirst()
                .orElseThrow(() -> new AssertionError("le solve n'a pourvu aucun poste"));
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
