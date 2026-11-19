package dev.sylvain.planning.service.solve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ai.timefold.solver.core.api.score.HardMediumSoftScore;
import ai.timefold.solver.core.api.solver.Solver;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Edition;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.EditionContext;
import dev.sylvain.planning.service.edition.EditionService;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.referentiel.TypologieItem;
import dev.sylvain.planning.service.solve.ProblemBuilder.ProblemeReamorce;
import dev.sylvain.planning.service.solve.SolverJobService.JobStatus;
import dev.sylvain.planning.service.solve.SolverJobService.ResultatSolve;
import dev.sylvain.planning.service.solve.SolverJobService.ResultatSolveIncremental;
import dev.sylvain.planning.service.solve.SolverJobService.SolverJob;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * The application started under {@code planning.solver.engine=hand} (ADR
 * 0087): a real job goes through the queue and everything a job owes the
 * operator is there at the end — its score curve, a stop that is honoured, the
 * plan persisted, the constraint analysis written — whichever engine searched.
 *
 * <p>The other tests of the direct engine build their solver by hand; this one
 * is the only proof that the instance setting reaches the seam, and that the
 * pipeline around it does not care.</p>
 */
@QuarkusTest
@TestProfile(SolverJobHandEngineTest.Profile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SolverJobHandEngineTest {

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("planning.solver.engine", "hand");
        }
    }

    private static final LocalDate FIRST_DAY = LocalDate.of(2030, 9, 3);
    private static final int STANDS = 3;
    private static final int ANIMATEURS = 10;

    /** Drawn by the application when the edition is created (ADR 0050). */
    private static String edition;

    @Inject
    SolverJobService solverJobs;

    @Inject
    SolvePipeline pipeline;

    @Inject
    SolverScoreTrace scoreTrace;

    @Inject
    PlanningService planningService;

    @Inject
    ReferenceDataService referenceData;

    @Inject
    PlanningPersistenceService persistence;

    @Inject
    ConstraintAnalysisStore analyses;

    @Inject
    EditionService editions;

    @Inject
    EditionContext editionContext;

    @BeforeEach
    void anEditionWithSomethingToSolve() {
        if (edition != null) {
            return;
        }
        edition = editions.create(new Edition(null, "Édition du moteur direct", false, null))
                .getId();
        editionContext.executeIn(edition, () -> {
            referenceData.createTypologie(new TypologieItem(null, "STRATEGIE", "Stratégie", false, null, null, null));
            for (int s = 0; s < STANDS; s++) {
                referenceData.createStand(new Stand("HAND-S" + s, "Stand " + s, Set.of("STRATEGIE"), 2, 2, false));
            }
            IntStream.range(0, ANIMATEURS)
                    .mapToObj(i -> new Animateur("HAND-A" + i, "Prenom", "Nom " + i, LocalDate.of(1990, 1, 1), false))
                    .forEach(referenceData::createAnimateur);
            for (int day = 0; day < 2; day++) {
                referenceData.createCreneau(
                        new Creneau(null, day + 1, FIRST_DAY.plusDays(day), LocalTime.of(9, 0), LocalTime.of(12, 0)));
                referenceData.createCreneau(
                        new Creneau(null, day + 1, FIRST_DAY.plusDays(day), LocalTime.of(14, 0), LocalTime.of(17, 0)));
            }
        });
    }

    @AfterAll
    static void forgetTheEdition() {
        edition = null;
    }

    @Test
    @Order(1)
    void theInstanceSettingReachesTheSeam() {
        awaitIdleSolver();
        AtomicReference<Solver<PlanningEvenement>> handed = new AtomicReference<>();

        SolvePipeline.Resolution<ProblemeReamorce> resolution = withinEdition(() -> pipeline.execute(
                edition,
                () -> planningService.buildFromReferenceData(Reamorcage.AUCUN),
                ProblemeReamorce::planning,
                SolveBudget.ofSeconds(1L),
                handed::set,
                () -> false));

        assertThat(handed.get()).isInstanceOf(HandSearchSolver.class);
        assertThat(resolution.interruption()).isNull();
    }

    @Test
    @Order(2)
    void aJobThroughTheQueueLeavesItsCurveItsPlanAndItsAnalysis() {
        awaitIdleSolver();

        SolverJob job = withinEdition(() -> solverJobs.submitSolveFromReferenceData(2L, false, Reamorcage.AUCUN));
        awaitEnd(job);

        assertThat(job.getStatus()).as("error: %s", job.getError()).isEqualTo(JobStatus.COMPLETED);
        String score = ((ResultatSolve) job.getResult()).diagnostic().score();
        assertThat(HardMediumSoftScore.parseScore(score).isFeasible()).isTrue();
        assertThat(job.getFeasibleAtSeconds()).isNotNull();
        SolverScoreTrace.Trace trace = scoreTrace.snapshot();
        assertThat(trace.jobId()).isEqualTo(job.getId());
        assertThat(trace.termine()).isTrue();
        assertThat(trace.points()).isNotEmpty();
        assertThat(trace.points().get(trace.points().size() - 1).hard()).isZero();
        assertThat(withinEdition(this::persistedHolders)).hasSize(STANDS * 2 * 4);
        ConstraintAnalysisStore.StoredAnalysis analysis = withinEdition(analyses::latest);
        assertThat(analysis).isNotNull();
        assertThat(analysis.diagnostic().score()).isEqualTo(score);
    }

    @Test
    @Order(3)
    void aRunningJobStoppedByHandEndsLongBeforeItsBudget() {
        awaitIdleSolver();
        SolverJob job = withinEdition(() -> solverJobs.submitSolveFromReferenceData(120L, false, Reamorcage.AUCUN));
        await().atMost(Duration.ofSeconds(60))
                .pollInterval(Duration.ofMillis(100))
                .until(
                        scoreTrace::snapshot,
                        trace -> trace != null
                                && job.getId().equals(trace.jobId())
                                && !trace.points().isEmpty());

        solverJobs.cancel(job.getId());
        awaitEnd(job);

        assertThat(job.getStatus()).isEqualTo(JobStatus.CANCELLED);
        assertThat(job.getElapsedSeconds()).isLessThan(60);
    }

    @Test
    @Order(4)
    void aReseededAndAnIncrementalSolveGoThroughTheQueueToo() {
        awaitIdleSolver();
        SolverJob full = withinEdition(() -> solverJobs.submitSolveFromReferenceData(2L, false, Reamorcage.AUCUN));
        awaitEnd(full);
        assertThat(full.getStatus()).as("error: %s", full.getError()).isEqualTo(JobStatus.COMPLETED);

        SolverJob reseeded =
                withinEdition(() -> solverJobs.submitSolveFromReferenceData(2L, false, Reamorcage.PLAN_COURANT));
        awaitEnd(reseeded);
        assertThat(reseeded.getStatus()).as("error: %s", reseeded.getError()).isEqualTo(JobStatus.COMPLETED);
        assertThat(((ResultatSolve) reseeded.getResult()).reamorcage().mode()).isEqualTo(Reamorcage.PLAN_COURANT);
        Map<String, String> before = withinEdition(this::persistedHolders);

        // The second day re-opened, the first left as it is.
        ReplanificationScope secondDay = new ReplanificationScope(Set.of(), Set.of(FIRST_DAY.plusDays(1)), Set.of());
        SolverJob incremental = withinEdition(() -> solverJobs.submitSolveIncremental(2L, secondDay, false));
        awaitEnd(incremental);

        assertThat(incremental.getStatus())
                .as("error: %s", incremental.getError())
                .isEqualTo(JobStatus.COMPLETED);
        ResultatSolveIncremental result = (ResultatSolveIncremental) incremental.getResult();
        assertThat(result.statistiques().postesFiges()).isEqualTo(STANDS * 2 * 2);
        assertThat(result.statistiques().postesLiberesManuellement()).isEqualTo(STANDS * 2 * 2);
        assertThat(HardMediumSoftScore.parseScore(result.diagnostic().score()).isFeasible())
                .isTrue();
        Map<String, String> firstDayBefore = withinEdition(() -> holdersOn(before, FIRST_DAY));
        assertThat(withinEdition(() -> holdersOn(persistedHolders(), FIRST_DAY)))
                .isNotEmpty()
                .isEqualTo(firstDayBefore);
    }

    /** Seat id → animateur id of the persisted plan. */
    private Map<String, String> persistedHolders() {
        return persistence.loadPersistedPlanning().getPostes().stream()
                .filter(poste -> poste.getAnimateur() != null)
                .collect(Collectors.toMap(
                        PosteAffectation::getId, poste -> poste.getAnimateur().getId()));
    }

    private Map<String, String> holdersOn(Map<String, String> holders, LocalDate day) {
        Set<String> seats = persistence.loadPersistedPlanning().getPostes().stream()
                .filter(poste -> poste.getCreneau().getDate().equals(day))
                .map(PosteAffectation::getId)
                .collect(Collectors.toSet());
        return holders.entrySet().stream()
                .filter(seat -> seats.contains(seat.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private <T> T withinEdition(java.util.concurrent.Callable<T> work) {
        return editionContext.executeIn(edition, work);
    }

    private void awaitEnd(SolverJob job) {
        await().atMost(Duration.ofSeconds(90))
                .pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> assertThat(job.isFinished())
                        .as("job %s: %s %s", job.getId(), job.getStatus(), job.getError())
                        .isTrue());
    }

    private void awaitIdleSolver() {
        await().atMost(Duration.ofSeconds(90))
                .pollInterval(Duration.ofMillis(250))
                .until(() -> solverJobs.findActive().isEmpty());
    }
}
