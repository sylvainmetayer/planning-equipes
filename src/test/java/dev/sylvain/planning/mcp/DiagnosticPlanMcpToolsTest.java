package dev.sylvain.planning.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sylvain.planning.mcp.SolveurMcpTools.DiagnosticPlanView;
import dev.sylvain.planning.mcp.SolveurMcpTools.JobMcpView;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.solve.SolverJobService;
import dev.sylvain.planning.service.solve.SolverJobService.JobStatus;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
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
    SolverJobService solverJobService;

    @Inject
    ReferenceDataService referenceDataService;

    @AfterEach
    void clearEdition() throws InterruptedException {
        awaitSolverIdle();
        scenarioTools.reinitialiser_donnees(null);
    }

    @Test
    void diagnosticScoresThePersistedPlanWithoutStartingASolver() throws InterruptedException {
        solvedPlanning();

        DiagnosticPlanView vue = solveurTools.diagnostiquer_plan(null);

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
        // The reading of the score goes out with it, the verdict first, and
        // names rules by their label — never an identifier, never a person.
        assertThat(vue.lecture()).isNotEmpty();
        assertThat(vue.lecture().getFirst()).containsPattern("règles? impératives?");
        assertThat(vue.lecture()).allSatisfy(phrase -> assertThat(phrase).doesNotContainPattern("\\b\\p{Ll}+\\p{Lu}"));
        assertThat(referenceDataService.listAnimateurs())
                .allSatisfy(animateur ->
                        assertThat(vue.lecture()).noneMatch(phrase -> phrase.contains(animateur.getNom())));

        // Same plan, same score: the diagnostic reads, it does not search.
        assertThat(solveurTools.diagnostiquer_plan(null).score()).isEqualTo(vue.score());
    }

    @Test
    void diagnosticWithoutAPersistedPlanSaysSo() throws InterruptedException {
        awaitSolverIdle();
        scenarioTools.reinitialiser_donnees(null);

        assertThatThrownBy(() -> solveurTools.diagnostiquer_plan(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("résolution");
    }

    private void solvedPlanning() throws InterruptedException {
        awaitSolverIdle();
        scenarioTools.reinitialiser_donnees(null);
        scenarioTools.importer_scenario("scenario.yml", null);
        JobMcpView job = solveurTools.lancer_solveur(1L, null, null, null);
        assertThat(awaitFinished(job.id()).status()).isEqualTo(JobStatus.COMPLETED.name());
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
