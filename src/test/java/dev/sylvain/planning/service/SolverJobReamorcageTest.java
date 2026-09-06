package dev.sylvain.planning.service;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import ai.timefold.solver.core.api.score.HardMediumSoftScore;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Edition;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.PlanningService.ProblemeReamorce;
import dev.sylvain.planning.service.SolverJobService.JobStatus;
import dev.sylvain.planning.service.SolverJobService.ResultatSolve;
import dev.sylvain.planning.service.SolverJobService.SolverJob;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

/**
 * The warm start of issue #174 end to end: where a full solve starts from,
 * what the job says about it, and the two guarantees the issue asks for —
 * a re-seeded solve never lands below the plan it started from, and an
 * infeasible seed buys no shortcut (the budget is spent, no misleading early
 * stop).
 */
@QuarkusTest
class SolverJobReamorcageTest {

    private static final LocalDate JOUR = LocalDate.of(2030, 9, 3);

    @Inject
    PlanningService planningService;

    @Inject
    ReferenceDataService referenceData;

    @Inject
    PlanningPersistenceService persistence;

    @Inject
    SolverJobService solverJobs;

    @Inject
    EditionService editions;

    @Inject
    EditionContext editionContext;

    /**
     * Every fixture lives in an edition of its own: the shared one carries the
     * other classes' leftovers (a scenario's animateurs, a stand or two), and a
     * "two seats, one animateur" problem is only infeasible when nobody else
     * is around to take the second seat.
     */
    private static final String EDITION = "WARM-EDITION";

    @Test
    void autoReseedsFromThePersistedPlanWithoutPinningAnything() {
        Fixture fixture = new Fixture(2, 2);
        try {
            fixture.create();
            fixture.persistPlan();

            ProblemeReamorce probleme = withinEdition(() -> planningService.buildFromReferenceData(Reamorcage.AUTO));

            assertThat(probleme.reamorcage()).isEqualTo(Reamorcage.PLAN_COURANT);
            assertThat(probleme.postesReamorces()).isEqualTo(2);
            assertThat(probleme.postesLiberes()).isZero();
            List<PosteAffectation> postes = fixture.postesDuProbleme(probleme.planning());
            assertThat(postes).hasSize(2).allMatch(poste -> poste.getAnimateur() != null);
            // The whole difference with the incremental re-solve, in one line.
            assertThat(postes).noneMatch(PosteAffectation::isVerrouille);
        } finally {
            fixture.clean();
        }
    }

    @Test
    void autoStartsColdWhenThereIsNoPlanAndPlanCourantRefusesTo() {
        Fixture fixture = new Fixture(1, 1);
        try {
            fixture.create();
            editionContext.executeIn(EDITION, () -> persistence.persist(new PlanningEvenement(JOUR, List.of(), List.of())));

            ProblemeReamorce probleme = withinEdition(() -> planningService.buildFromReferenceData(Reamorcage.AUTO));
            assertThat(probleme.reamorcage()).isEqualTo(Reamorcage.AUCUN);
            assertThat(probleme.postesReamorces()).isZero();

            assertThatThrownBy(() -> withinEdition(() -> planningService.buildFromReferenceData(Reamorcage.PLAN_COURANT)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Aucun plan enregistré");
        } finally {
            fixture.clean();
        }
    }

    @Test
    void aucunStartsColdEvenWhenAPlanExists() {
        Fixture fixture = new Fixture(2, 2);
        try {
            fixture.create();
            fixture.persistPlan();

            ProblemeReamorce probleme = withinEdition(() -> planningService.buildFromReferenceData(Reamorcage.AUCUN));

            assertThat(probleme.reamorcage()).isEqualTo(Reamorcage.AUCUN);
            assertThat(fixture.postesDuProbleme(probleme.planning())).allMatch(poste -> poste.getAnimateur() == null);
        } finally {
            fixture.clean();
        }
    }

    /**
     * The point of the feature, as the job reports it: the seed is kept, and
     * the result is never below it. The seed is a plan the solver itself
     * produced on the whole referential — the edition carries other tests'
     * leftovers, and a hand-made two-seat plan would leave every other seat
     * for the construction heuristic to fill, which makes its score
     * incomparable to the solve's.
     */
    @Test
    void aReseededSolveReportsItselfAndNeverLandsBelowTheSeed() throws InterruptedException {
        Fixture fixture = new Fixture(2, 2);
        try {
            fixture.create();
            attendreSolveurLibre();
            SolverJob froid = withinEdition(() -> solverJobs.submitSolveFromReferenceData(2L, false, Reamorcage.AUCUN));
            attendreFin(froid);
            assertThat(froid.getStatus()).isEqualTo(JobStatus.COMPLETED);
            ResultatSolve seed = (ResultatSolve) froid.getResult();
            assertThat(seed.reamorcage().mode()).isEqualTo(Reamorcage.AUCUN);
            int affectations = withinEdition(persistence::countPersistedAssignments);
            assertThat(affectations).isPositive();

            SolverJob chaud = withinEdition(() -> solverJobs.submitSolveFromReferenceData(2L, false, Reamorcage.AUTO));
            attendreFin(chaud);

            assertThat(chaud.getStatus()).isEqualTo(JobStatus.COMPLETED);
            ResultatSolve resultat = (ResultatSolve) chaud.getResult();
            assertThat(resultat.reamorcage().mode()).isEqualTo(Reamorcage.PLAN_COURANT);
            // At least this fixture's two seats, at most every persisted one: a
            // leftover of another test may hold a seat the referential no longer
            // produces, or a tenant who has since been declared unavailable.
            assertThat(resultat.reamorcage().postes()).isBetween(2, affectations);
            assertThat(HardMediumSoftScore.parseScore(resultat.diagnostic().score()))
                    .isGreaterThanOrEqualTo(HardMediumSoftScore.parseScore(seed.diagnostic().score()));
        } finally {
            fixture.clean();
        }
    }

    /**
     * Two seats, one animateur: no plan can ever be feasible, so the
     * feasibility termination never fires and the solve runs its whole budget
     * — re-seeded or not. What the issue asks to check is that the warm start
     * does not fake an early stop here.
     */
    @Test
    void anInfeasibleSeedSpendsTheWholeBudget() throws InterruptedException {
        Fixture fixture = new Fixture(2, 1);
        try {
            fixture.create();
            fixture.persistPlan();
            attendreSolveurLibre();

            Instant debut = Instant.now();
            SolverJob job = withinEdition(() -> solverJobs.submitSolveFromReferenceData(2L, false, Reamorcage.PLAN_COURANT));
            attendreFin(job);

            assertThat(job.getStatus()).isEqualTo(JobStatus.COMPLETED);
            ResultatSolve resultat = (ResultatSolve) job.getResult();
            assertThat(resultat.reamorcage().mode()).isEqualTo(Reamorcage.PLAN_COURANT);
            assertThat(HardMediumSoftScore.parseScore(resultat.diagnostic().score()).hardScore()).isNegative();
            assertThat(Duration.between(debut, Instant.now())).isGreaterThanOrEqualTo(Duration.ofMillis(1500));
        } finally {
            fixture.clean();
        }
    }

    @Test
    void anUnknownReamorcageValueIsA400() {
        given().when().post("/api/solve/async/reference-data?reamorcage=BIDON")
                .then().statusCode(400);
    }

    private <T> T withinEdition(java.util.concurrent.Callable<T> travail) {
        return editionContext.executeIn(EDITION, travail);
    }

    private void attendreFin(SolverJob job) throws InterruptedException {
        for (int essai = 0; essai < 240; essai++) {
            if (List.of(JobStatus.COMPLETED, JobStatus.FAILED, JobStatus.CANCELLED).contains(job.getStatus())) {
                return;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("Job " + job.getId() + " did not finish: " + job.getStatus() + " " + job.getError());
    }

    private void attendreSolveurLibre() throws InterruptedException {
        for (int essai = 0; essai < 240 && solverJobs.findActive().isPresent(); essai++) {
            Thread.sleep(250);
        }
    }

    /** One stand with {@code sieges} seats on one créneau, and {@code effectif} animateurs to fill them. */
    private final class Fixture {
        private final int sieges;
        private final int effectif;
        private final Stand stand;
        private final List<Animateur> animateurs;
        private Creneau creneau;

        Fixture(int sieges, int effectif) {
            this.sieges = sieges;
            this.effectif = effectif;
            this.stand = new Stand("WARM-S1", "Stand warm", Set.of("STRATEGIE"), sieges, sieges, false);
            this.animateurs = java.util.stream.IntStream.range(0, effectif)
                    .mapToObj(i -> new Animateur("WARM-A" + i, "Prenom", "Nom " + i, LocalDate.of(1990, 1, 1), false))
                    .toList();
        }

        void create() {
            if (editions.listEditions().stream().noneMatch(edition -> EDITION.equals(edition.getId()))) {
                editions.create(new Edition(EDITION, "Édition du réamorçage", false, null));
            }
            editionContext.executeIn(EDITION, () -> {
                clean();
                // A creation whose id is taken is a 409 since the write carries
                // its own precondition (issue #362): the fixture runs once per
                // test, the typologie survives clean().
                if (referenceData.listTypologies().stream().noneMatch(item -> "STRATEGIE".equals(item.id()))) {
                    referenceData.createTypologie(new TypologieItem("STRATEGIE", "Stratégie"));
                }
                referenceData.createStand(stand);
                animateurs.forEach(referenceData::createAnimateur);
                creneau = referenceData.createCreneau(
                        new Creneau(null, 1, JOUR, LocalTime.of(9, 0), LocalTime.of(12, 0)));
            });
        }

        /** Every seat staffed, the animateurs reused in turn — infeasible on purpose when there are fewer of them. */
        void persistPlan() {
            List<PosteAffectation> postes = new java.util.ArrayList<>();
            for (int i = 0; i < sieges; i++) {
                PosteAffectation poste = new PosteAffectation("WARM-P" + i, stand, creneau);
                poste.setAnimateur(animateurs.get(i % effectif));
                postes.add(poste);
            }
            editionContext.executeIn(EDITION, () -> persistence.persist(new PlanningEvenement(JOUR, animateurs, postes)));
        }

        /** This fixture's seats only: the edition may hold other tests' créneaux, on which the stand also opens. */
        List<PosteAffectation> postesDuProbleme(PlanningEvenement planning) {
            return planning.getPostes().stream()
                    .filter(poste -> "WARM-S1".equals(poste.getStand().getId())
                            && creneau.getId().equals(poste.getCreneau().getId()))
                    .toList();
        }

        void clean() {
            try {
                attendreSolveurLibre();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            editionContext.executeIn(EDITION, () -> {
                persistence.persist(new PlanningEvenement(JOUR, List.of(), List.of()));
                referenceData.deleteStand("WARM-S1");
                animateurs.forEach(animateur -> referenceData.deleteAnimateur(animateur.getId()));
                referenceData.deleteCreneaux(referenceData.listCreneaux().stream().map(Creneau::getId).toList());
            });
        }
    }
}
