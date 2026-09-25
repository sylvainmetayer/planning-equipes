package dev.sylvain.planning.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import dev.sylvain.planning.mcp.PlanningMcpTools.AffectationView;
import dev.sylvain.planning.mcp.PlanningMcpTools.SuggestionsView;
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
 * Repairing one seat without re-solving: find the candidates, apply one, and
 * be refused on a seat the operator has frozen.
 *
 * <p>The refusal matters as much as the repair. A lock is what an assistant is
 * told to respect, and {@code affecter_poste} is the one tool here that could
 * walk over it silently.</p>
 */
@QuarkusTest
class ReparationMcpToolsTest {

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
    VerrouillageMcpTools verrouillageTools;

    @Inject
    SolverJobService solverJobService;

    @AfterEach
    void clearEdition() {
        awaitSolverIdle();
        scenarioTools.resetData(null);
    }

    @Test
    void suggestsCandidatesThenAppliesOne() {
        AffectationView poste = premierPostePourvu();

        SuggestionsView suggestions = planningTools.suggestRepairs(poste.posteId(), 5, null);

        assertThat(suggestions.posteId()).isEqualTo(poste.posteId());
        assertThat(suggestions.animateurActuelId()).isEqualTo(poste.animateurId());
        assertThat(suggestions.candidatsEvalues()).isLessThanOrEqualTo(suggestions.candidatsEligibles());
        assertThat(suggestions.suggestions())
                .allSatisfy(suggestion -> assertThat(suggestion.animateurId()).isNotBlank());
    }

    @Test
    void assigningAPosteHandsItOverWithoutASolve() {
        AffectationView poste = premierPostePourvu();

        var reaffectation = planningTools.assignPoste(poste.posteId(), null, null);

        assertThat(reaffectation.animateurPrecedentId()).isEqualTo(poste.animateurId());
        assertThat(reaffectation.animateurId()).isNull();
        assertThat(planningTools
                        .listAffectations(null, null, null, null, null, null)
                        .affectations())
                .filteredOn(vue -> vue.posteId().equals(poste.posteId()))
                .singleElement()
                .satisfies(vue -> assertThat(vue.animateurId()).isNull());
        assertThat(solverJobService.findActive()).isEmpty();
    }

    @Test
    void assigningALockedPosteIsRefused() {
        AffectationView poste = premierPostePourvu();
        VerrouillageView verrou = verrouillageTools
                .lock("ANIMATEUR", poste.animateurId(), null, null, null, null, null)
                .verrouillage();

        String posteId = poste.posteId();
        assertThatThrownBy(() -> planningTools.assignPoste(posteId, null, null))
                .isInstanceOf(ToolCallException.class)
                .hasCauseInstanceOf(BusinessError.Invalid.class)
                .hasMessageContaining("verrouillé");

        verrouillageTools.unlock(verrou.id(), null);
    }

    @Test
    void suggestingOnAnUnknownPosteIsRefused() {
        premierPostePourvu();

        assertThatThrownBy(() -> planningTools.suggestRepairs("POSTE-INCONNU", null, null))
                .isInstanceOf(RuntimeException.class);
    }

    /**
     * A business refusal since the assistant reads the persisted plan through
     * {@code persistedSuggererReparations}: the sentence reaches the caller as
     * a tool result in error (issue #529) instead of « Internal error ».
     */
    @Test
    void suggestingWithoutAPersistedPlanningSaysSo() {
        scenarioTools.resetData(null);

        assertThatThrownBy(() -> planningTools.suggestRepairs("P1", null, null))
                .isInstanceOf(ToolCallException.class)
                .hasCauseInstanceOf(BusinessError.Conflict.class)
                .hasMessageContaining("résolution");
    }

    private AffectationView premierPostePourvu() {
        awaitSolverIdle();
        scenarioTools.resetData(null);
        scenarioTools.importScenario("scenario.yml", null);
        JobMcpView job = solveurTools.startSolver(1L, null, null, null);
        assertThat(awaitFinished(job.id()).status()).isEqualTo(JobStatus.COMPLETED.name());
        return planningTools.listAffectations(null, null, null, null, null, null).affectations().stream()
                .filter(vue -> vue.animateurId() != null)
                .findFirst()
                .orElseThrow(() -> new AssertionError("le solve n'a pourvu aucun poste"));
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
