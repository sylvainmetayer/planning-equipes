package dev.sylvain.planning.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import dev.sylvain.planning.mcp.SolveurMcpTools.DiagnosticPlanView;
import dev.sylvain.planning.mcp.SolveurMcpTools.JobMcpView;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.solve.SolverJobService;
import dev.sylvain.planning.service.solve.SolverJobService.JobStatus;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@code diagnostiquer_plan} replaces a tool that launched a full solve and
 * threw its result away.
 *
 * <p>The two properties that made the replacement worth doing are what this
 * test holds: the score describes the plan that is <b>persisted</b> — the one
 * every other tool reports on — and producing it starts no solver.</p>
 */
@QuarkusTest
class DiagnosticPlanMcpToolsTest {

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
    SolverJobService solverJobService;

    @Inject
    ReferenceDataService referenceDataService;

    @AfterEach
    void clearEdition() {
        awaitSolverIdle();
        scenarioTools.resetData(null);
    }

    @Test
    void diagnosticScoresThePersistedPlanWithoutStartingASolver() {
        solvedPlanning();

        DiagnosticPlanView vue = solveurTools.diagnosePlan(null);

        assertThat(vue.score()).isNotBlank();
        assertThat(vue.postesNonPourvus()).isGreaterThanOrEqualTo(0);
        assertThat(vue.contraintes()).isNotEmpty().allSatisfy(contrainte -> {
            assertThat(contrainte.name()).isNotBlank();
            assertThat(contrainte.score()).isNotBlank();
            assertThat(contrainte.nombreCorrespondances()).isGreaterThanOrEqualTo(0);
        });
        assertThat(solverJobService.findActive())
                .as("un diagnostic ne lance aucune résolution")
                .isEmpty();
        // Every rule in default carries its playbook, by code: an assistant
        // proposes the gesture the Diagnostic offers, never a route.
        assertThat(vue.contraintes())
                .filteredOn(contrainte -> SolveurMcpTools.penalises(contrainte.score()))
                .isNotEmpty()
                .allSatisfy(contrainte -> assertThat(contrainte.actions())
                        .isNotEmpty()
                        .allSatisfy(action -> assertThat(action.code()).matches("[A-Z_]+")));
        assertThat(vue.contraintes())
                .filteredOn(contrainte -> !SolveurMcpTools.penalises(contrainte.score()))
                .allSatisfy(contrainte -> assertThat(contrainte.actions()).isEmpty());
        // The reading of the score goes out with it, the verdict first, and
        // names rules by their label — never an identifier, never a person.
        assertThat(vue.lecture())
                .isNotEmpty()
                .allSatisfy(phrase -> assertThat(phrase).doesNotContainPattern("\\b\\p{Ll}+\\p{Lu}"));
        assertThat(vue.lecture().getFirst()).containsPattern("règles? impératives?");
        assertThat(referenceDataService.listAnimateurs())
                .isNotEmpty()
                .allSatisfy(animateur -> assertThat(vue.lecture())
                        .isNotEmpty()
                        .noneMatch(phrase -> phrase.contains(animateur.getNom())));

        // Same plan, same score: the diagnostic reads, it does not search.
        assertThat(solveurTools.diagnosePlan(null).score()).isEqualTo(vue.score());
    }

    @Test
    void diagnosticWithoutAPersistedPlanSaysSo() {
        awaitSolverIdle();
        scenarioTools.resetData(null);

        assertThatThrownBy(() -> solveurTools.diagnosePlan(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("résolution");
    }

    private void solvedPlanning() {
        awaitSolverIdle();
        scenarioTools.resetData(null);
        scenarioTools.importScenario("scenario.yml", null);
        JobMcpView job = solveurTools.startSolver(1L, null, null, null);
        assertThat(awaitFinished(job.id()).status()).isEqualTo(JobStatus.COMPLETED.name());
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
