package dev.sylvain.planning.service.solve;

import static org.assertj.core.api.Assertions.assertThat;

import ai.timefold.solver.core.api.score.HardMediumSoftScore;
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
import dev.sylvain.planning.service.solve.SolverJobService.SolverJob;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * A solve the container stops under — a graceful shutdown, a live reload —
 * must never end {@code COMPLETED} with its partial plan written over the
 * persisted one. Timefold treats the stop as an ordinary termination and
 * hands back its best solution so far; the job has to say the run was cut
 * short, and that plan replaces the persisted one only when it is better.
 *
 * <p>Runs under its own profile: {@link SolverJobService#shutdown()} retires
 * the worker pool for good, so the last test here leaves the service unusable
 * and the application is restarted before the next class.</p>
 */
@QuarkusTest
@TestProfile(SolverJobArretTest.Profil.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SolverJobArretTest {

    public static class Profil implements QuarkusTestProfile {}

    private static final LocalDate JOUR = LocalDate.of(2030, 9, 3);
    private static final String EDITION = "ARRET-EDITION";

    @Inject
    SolverJobService solverJobs;

    @Inject
    SolvePipeline pipeline;

    @Inject
    PlanningService planningService;

    @Inject
    ReferenceDataService referenceData;

    @Inject
    PlanningPersistenceService persistence;

    @Inject
    EditionService editions;

    @Inject
    EditionContext editionContext;

    /**
     * The persisted plan is the one to protect: a run stopped right after it
     * started scores no better than what a finished solve reached, so it is
     * discarded and the plan in place stays byte for byte.
     */
    @Test
    @Order(1)
    void aPartialPlanThatDoesNotBeatThePersistedOneIsDiscarded() throws InterruptedException {
        Fixture fixture = new Fixture(2, 1);
        try {
            fixture.create();
            attendreSolveurLibre();
            SolverJob abouti =
                    withinEdition(() -> solverJobs.submitSolveFromReferenceData(2L, false, Reamorcage.AUCUN));
            attendreFin(abouti);
            assertThat(abouti.getStatus()).isEqualTo(JobStatus.COMPLETED);
            String scoreAbouti =
                    ((ResultatSolve) abouti.getResult()).diagnostic().score();
            Map<String, String> planAbouti = withinEdition(this::affectationsPersistees);
            assertThat(planAbouti).isNotEmpty();

            // The server "goes down" a moment after the solver started — long
            // enough for this two-seat problem to reach the same optimum, not
            // to beat it. Timefold resets an early termination asked before
            // solve() starts, hence the delay.
            SolvePipeline.Resolution<ProblemeReamorce> resolution = withinEdition(() -> pipeline.execute(
                    EDITION,
                    () -> planningService.buildFromReferenceData(Reamorcage.AUCUN),
                    ProblemeReamorce::planning,
                    30L,
                    solver -> Thread.ofVirtual().start(() -> {
                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        solver.terminateEarly();
                    }),
                    () -> true));

            assertThat(resolution.interruption()).isNotNull();
            assertThat(resolution.interruption().partialPlanKept()).isFalse();
            assertThat(resolution.interruption().persistedScore()).isEqualTo(scoreAbouti);
            assertThat(HardMediumSoftScore.parseScore(resolution.interruption().partialScore()))
                    .isLessThanOrEqualTo(HardMediumSoftScore.parseScore(scoreAbouti));
            assertThat(withinEdition(this::affectationsPersistees)).isEqualTo(planAbouti);
        } finally {
            fixture.clean();
        }
    }

    /**
     * The bug of the live reload, staged for real: the container stops under
     * a running job. It ends {@code INTERROMPU} with a message saying what
     * became of its plan — kept here, since nothing was persisted before —
     * and never {@code COMPLETED}.
     */
    @Test
    @Order(2)
    void aSolveStoppedByTheContainerEndsInterrompuAndKeepsItsPlanWhenNothingWasStored() throws InterruptedException {
        // Two seats, one animateur: infeasible, so the solve runs its whole
        // budget instead of stopping on feasibility before the shutdown.
        Fixture fixture = new Fixture(2, 1);
        try {
            fixture.create();
            attendreSolveurLibre();
            assertThat(withinEdition(this::affectationsPersistees)).isEmpty();
            SolverJob job = withinEdition(() -> solverJobs.submitSolveFromReferenceData(60L, false, Reamorcage.AUCUN));
            attendreDemarrage(job);

            solverJobs.shutdown();

            assertThat(job.getStatus()).isEqualTo(JobStatus.INTERROMPU);
            assertThat(job.isFinished()).isTrue();
            assertThat(job.getError()).contains("arrêt du serveur").contains("enregistré");
            ResultatSolve resultat = (ResultatSolve) job.getResult();
            assertThat(resultat.interruption()).isNotNull();
            assertThat(resultat.interruption().partialPlanKept()).isTrue();
            assertThat(resultat.interruption().persistedScore()).isNull();
            assertThat(withinEdition(this::affectationsPersistees)).isNotEmpty();
            assertThat(solverJobs.findActive()).isEmpty();
        } finally {
            fixture.clean();
        }
    }

    /** Seat id → animateur id of the persisted plan, the shape a comparison needs. */
    private Map<String, String> affectationsPersistees() {
        return persistence.loadPersistedPlanning().getPostes().stream()
                .filter(poste -> poste.getAnimateur() != null)
                .collect(Collectors.toMap(
                        PosteAffectation::getId, poste -> poste.getAnimateur().getId()));
    }

    private <T> T withinEdition(java.util.concurrent.Callable<T> travail) {
        return editionContext.executeIn(EDITION, travail);
    }

    private void attendreFin(SolverJob job) throws InterruptedException {
        for (int essai = 0; essai < 240 && !job.isFinished(); essai++) {
            Thread.sleep(250);
        }
        assertThat(job.isFinished())
                .as("job %s: %s %s", job.getId(), job.getStatus(), job.getError())
                .isTrue();
    }

    /** Until the job holds a running solver, not just the RUNNING status it takes while building its problem. */
    private void attendreDemarrage(SolverJob job) throws InterruptedException {
        for (int essai = 0; essai < 240 && job.getStatus() != JobStatus.RUNNING; essai++) {
            Thread.sleep(250);
        }
        assertThat(job.getStatus()).isEqualTo(JobStatus.RUNNING);
        Thread.sleep(1500);
    }

    private void attendreSolveurLibre() throws InterruptedException {
        for (int essai = 0; essai < 240 && solverJobs.findActive().isPresent(); essai++) {
            Thread.sleep(250);
        }
    }

    /** One stand with {@code sieges} seats on one créneau, and {@code effectif} animateurs to fill them. */
    private final class Fixture {
        private final Stand stand;
        private final List<Animateur> animateurs;

        Fixture(int sieges, int effectif) {
            this.stand = new Stand("ARRET-S1", "Stand arrêt", Set.of("STRATEGIE"), sieges, sieges, false);
            this.animateurs = IntStream.range(0, effectif)
                    .mapToObj(i -> new Animateur("ARRET-A" + i, "Prenom", "Nom " + i, LocalDate.of(1990, 1, 1), false))
                    .toList();
        }

        void create() {
            if (editions.listEditions().stream().noneMatch(edition -> EDITION.equals(edition.getId()))) {
                editions.create(new Edition(EDITION, "Édition de l'arrêt", false, null));
            }
            editionContext.executeIn(EDITION, () -> {
                clean();
                if (referenceData.listTypologies().stream().noneMatch(item -> "STRATEGIE".equals(item.id()))) {
                    referenceData.createTypologie(new TypologieItem("STRATEGIE", "Stratégie"));
                }
                referenceData.createStand(stand);
                animateurs.forEach(referenceData::createAnimateur);
                referenceData.createCreneau(new Creneau(null, 1, JOUR, LocalTime.of(9, 0), LocalTime.of(12, 0)));
            });
        }

        void clean() {
            editionContext.executeIn(EDITION, () -> {
                persistence.persist(new PlanningEvenement(JOUR, List.of(), List.of()));
                referenceData.deleteStand("ARRET-S1");
                animateurs.forEach(animateur -> referenceData.deleteAnimateur(animateur.getId()));
                referenceData.deleteCreneaux(referenceData.listCreneaux().stream()
                        .map(Creneau::getId)
                        .toList());
            });
        }
    }
}
