package dev.sylvain.planning.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Edition;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;

/**
 * Deleting a stand or an animateur a persisted plan still references — the twin
 * of the créneau case fixed for issue #281.
 *
 * <p>{@code poste_affectation} is the only table referencing {@code stand} and
 * {@code animateur} whose foreign key neither cascades nor nulls out, so both
 * deletes came back as a 500 as soon as a plan existed, that is after any solve
 * at all.</p>
 *
 * <p>The two cases then part ways, and these tests pin the difference. A stand's
 * seats <b>go</b>: {@code stand_id} is {@code NOT NULL}, a seat cannot outlive
 * its stand. An animateur's seats are <b>vacated</b>: {@code animateur_id} is
 * nullable, and a row holding {@code NULL} is exactly how this model spells
 * "place non pourvue", so the hole stays visible instead of the seat quietly
 * disappearing from the counts.</p>
 */
@QuarkusTest
class ReferenceDataServiceSuppressionTest {

    @Inject
    ReferenceDataService referenceData;

    @Inject
    PlanningPersistenceService persistence;

    @Inject
    PlanningService planningService;

    @Inject
    SolverJobService solverJobs;

    @Inject
    EditionService editions;

    @Inject
    EditionContext editionContext;

    private static final LocalDate JOUR = LocalDate.of(2030, 7, 3);

    private static final String EDITION_VOISINE = "SUP-EDITION-B";

    /** Deleting a stand takes the seats that were opened on it, and only those. */
    @Test
    void supprimerUnStandEmporteLesPostesQuiLeReferencaient() {
        Creneau creneau = creneau(9501L);
        Stand cible = stand("SUP-S1");
        Stand voisin = stand("SUP-S2");
        Animateur animateur = animateur("SUP-A1");
        try {
            persistence.persist(new PlanningEvenement(JOUR, List.of(animateur),
                    List.of(poste("SUP-P1", cible, creneau, animateur),
                            poste("SUP-P2", voisin, creneau, animateur))));
            assertThat(postesForStand("SUP-S1")).isNotEmpty();

            referenceData.deleteStand("SUP-S1");

            assertThat(referenceData.listStands()).noneMatch(s -> "SUP-S1".equals(s.getId()));
            assertThat(postesForStand("SUP-S1")).isEmpty();
            // The rest of the plan is untouched: only the deleted stand's seats go.
            assertThat(postesForStand("SUP-S2")).hasSize(1);
        } finally {
            nettoyer();
        }
    }

    /**
     * An animateur's seats are vacated rather than removed: each becomes a place
     * non pourvue the diagnostic screens can still see, and still count.
     */
    @Test
    void supprimerUnAnimateurLaisseSesPostesEnPlaceNonPourvue() {
        Creneau creneau = creneau(9502L);
        Stand stand = stand("SUP-S3");
        Animateur cible = animateur("SUP-A2");
        Animateur voisin = animateur("SUP-A3");
        try {
            persistence.persist(new PlanningEvenement(JOUR, List.of(cible, voisin),
                    List.of(poste("SUP-P3", stand, creneau, cible),
                            poste("SUP-P4", stand, creneau, voisin))));
            assertThat(postesForAnimateur("SUP-A2")).isNotEmpty();
            assertThat(persistence.countPersistedAssignments()).isEqualTo(2);

            referenceData.deleteAnimateur("SUP-A2");

            assertThat(referenceData.listAnimateurs()).noneMatch(a -> "SUP-A2".equals(a.getId()));
            // The seat stays; only its occupant goes.
            assertThat(persistence.countPersistedAssignments()).isEqualTo(2);
            assertThat(postesForAnimateur("SUP-A2")).isEmpty();
            assertThat(postesForAnimateur("SUP-A3")).hasSize(1);
            assertThat(persistence.loadPersistedPlanning().getPostes())
                    .filteredOn(poste -> "SUP-P3".equals(poste.getId()))
                    .singleElement()
                    .satisfies(poste -> {
                        assertThat(poste.getAnimateur()).isNull();
                        assertThat(poste.getStand().getId()).isEqualTo("SUP-S3");
                    });

            // And the hole is what the diagnostic reads: had the row been deleted
            // instead, coverage would have come back greener than reality.
            assertThat(planningService.diagnosePersistedPlan().postesNonPourvus()).isEqualTo(1);
        } finally {
            nettoyer();
        }
    }

    /**
     * Bulk deletion is the same statement repeated — the frontend's
     * {@code removeMany} issues one {@code DELETE /api/stands/{id}} per row, one
     * transaction each — so a lot whose every member holds seats must go through
     * whole rather than stop on the first referenced one. It also shows the two
     * rules side by side: the animateurs of the lot leave their seats behind,
     * vacant; the stands of the lot take theirs away.
     */
    @Test
    void supprimerUnLotDeStandsEtDAnimateursReferencesPasseEnEntier() {
        Creneau creneau = creneau(9503L);
        List<Stand> stands = List.of(stand("SUP-S4"), stand("SUP-S5"), stand("SUP-S6"));
        List<Animateur> animateurs = List.of(animateur("SUP-A4"), animateur("SUP-A5"), animateur("SUP-A6"));
        try {
            persistence.persist(new PlanningEvenement(JOUR, animateurs,
                    List.of(poste("SUP-P5", stands.get(0), creneau, animateurs.get(0)),
                            poste("SUP-P6", stands.get(1), creneau, animateurs.get(1)),
                            poste("SUP-P7", stands.get(2), creneau, animateurs.get(2)))));
            assertThat(persistence.countPersistedAssignments()).isEqualTo(3);

            animateurs.forEach(animateur -> referenceData.deleteAnimateur(animateur.getId()));

            // Every seat of the lot is vacant now, and still there to be seen.
            assertThat(referenceData.listAnimateurs()).noneMatch(a -> a.getId().startsWith("SUP-A"));
            assertThat(persistence.countPersistedAssignments()).isEqualTo(3);
            assertThat(persistence.loadPersistedPlanning().getPostes())
                    .filteredOn(poste -> poste.getId().startsWith("SUP-P"))
                    .hasSize(3)
                    .allSatisfy(poste -> assertThat(poste.getAnimateur()).isNull());

            stands.forEach(stand -> referenceData.deleteStand(stand.getId()));

            // The stands, themselves, do take their seats with them.
            assertThat(referenceData.listStands()).noneMatch(s -> s.getId().startsWith("SUP-S"));
            assertThat(persistence.countPersistedAssignments()).isZero();
        } finally {
            nettoyer();
        }
    }


    /**
     * The race the fix above made reachable, and that this guard closes.
     *
     * <p>A solve builds its problem from the referential at its start and
     * persists the result at the end — and that persist re-upserts every stand
     * and animateur its result names. Deleting one meanwhile therefore used to
     * be undone by the solve landing, minutes later: the animateur was back,
     * reattached to their seats. On a base holding minors, that is personal data
     * returning on its own, so the delete is refused while the solver is held
     * rather than silently reverted.</p>
     *
     * <p>Not a defect this PR introduced — before it, the delete failed with a
     * 500 and never got far enough to be reverted. Making the delete work is
     * what makes the race reachable, which is why it is closed here.</p>
     */
    @Test
    void supprimerPendantUnSolveEstRefuseAuLieuDetreAnnuleParLatterrissage() {
        Creneau creneau = creneau(9504L);
        Stand stand = stand("SUP-S7");
        Animateur animateur = animateur("SUP-A7");
        PlanningEvenement probleme = new PlanningEvenement(JOUR, List.of(animateur),
                List.of(poste("SUP-P8", stand, creneau, animateur)));
        String jobId = null;
        try {
            persistence.persist(probleme);
            attendreSolveurLibre();

            // The solve is now holding the solver, with the referential of its
            // start captured in its problem — exactly the window in which a
            // delete would be undone by the landing.
            jobId = solverJobs.submitSolve(probleme, 60L).getId();
            assertThat(solverJobs.findActive()).isPresent();

            assertThatThrownBy(() -> referenceData.deleteAnimateur("SUP-A7"))
                    .isInstanceOf(SolverJobService.SolverBusyException.class);
            assertThatThrownBy(() -> referenceData.deleteStand("SUP-S7"))
                    .isInstanceOf(SolverJobService.SolverBusyException.class);

            // Refused, so still there — and still there for the solve to land on.
            assertThat(referenceData.listAnimateurs()).anyMatch(a -> "SUP-A7".equals(a.getId()));
            assertThat(referenceData.listStands()).anyMatch(st -> "SUP-S7".equals(st.getId()));
        } finally {
            if (jobId != null) {
                solverJobs.cancel(jobId);
            }
            // The cancelled solve still persists its best solution: let it land
            // before cleaning, or it would put the fixture back after the wipe.
            attendreSolveurLibre();
            nettoyer();
        }
    }


    /**
     * The guard must not refuse beyond the danger: a solve on edition A cannot
     * resurrect anything in edition B.
     *
     * <p>A job runs inside {@code editionContext.executeIn(job.getEditionId(),
     * …)}, so its landing persist writes to its own edition only. Refusing in B
     * anyway would break what the queue is for — the README's promise of
     * preparing the next edition without waiting in front of the screen — and
     * the default budget being 900 s (V57), that wait is not theoretical.</p>
     *
     * <p>The comparison is on the <b>job's</b> edition, not on whoever submitted
     * it: the solve below is launched from A and stays blocking for A even
     * though the assertions then run with the context moved to B.</p>
     */
    @Test
    void supprimerDansUneAutreEditionEstAccepteePendantUnSolve() {
        Creneau creneau = creneau(9506L);
        Stand stand = stand("SUP-S9");
        Animateur animateur = animateur("SUP-A9");
        PlanningEvenement probleme = new PlanningEvenement(JOUR, List.of(animateur),
                List.of(poste("SUP-P10", stand, creneau, animateur)));
        String jobId = null;
        editions.create(new Edition(EDITION_VOISINE, "Édition voisine", false, null));
        try {
            // Edition B gets a referential of its own, through the edition scope.
            editionContext.executeIn(EDITION_VOISINE, () -> {
                referenceData.createStand(stand("SUP-S10"));
                referenceData.createAnimateur(animateur("SUP-A10"));
            });

            persistence.persist(probleme);
            attendreSolveurLibre();

            // The solve holds the solver, for edition A (the default one).
            jobId = solverJobs.submitSolve(probleme, 60L).getId();
            assertThat(solverJobs.findActive()).isPresent();
            assertThat(solverJobs.findActive().orElseThrow().getEditionId())
                    .isEqualTo(editionContext.editionIdCourant());

            // In B, nothing is at risk: the deletes go through.
            editionContext.executeIn(EDITION_VOISINE, () -> {
                referenceData.deleteAnimateur("SUP-A10");
                referenceData.deleteStand("SUP-S10");
                assertThat(referenceData.listAnimateurs()).noneMatch(a -> "SUP-A10".equals(a.getId()));
                assertThat(referenceData.listStands()).noneMatch(st -> "SUP-S10".equals(st.getId()));
            });

            // And A is still protected by the very same running job.
            assertThatThrownBy(() -> referenceData.deleteAnimateur("SUP-A9"))
                    .isInstanceOf(SolverJobService.SolverBusyException.class);
        } finally {
            if (jobId != null) {
                solverJobs.cancel(jobId);
            }
            attendreSolveurLibre();
            editions.delete(EDITION_VOISINE);
            nettoyer();
        }
    }

    /** Once the solver is free again, the same delete goes through. */
    @Test
    void supprimerUneFoisLeSolveTermineFonctionne() {
        Creneau creneau = creneau(9505L);
        Stand stand = stand("SUP-S8");
        Animateur animateur = animateur("SUP-A8");
        try {
            persistence.persist(new PlanningEvenement(JOUR, List.of(animateur),
                    List.of(poste("SUP-P9", stand, creneau, animateur))));
            attendreSolveurLibre();

            referenceData.deleteAnimateur("SUP-A8");
            referenceData.deleteStand("SUP-S8");

            assertThat(referenceData.listAnimateurs()).noneMatch(a -> "SUP-A8".equals(a.getId()));
            assertThat(referenceData.listStands()).noneMatch(st -> "SUP-S8".equals(st.getId()));
        } finally {
            nettoyer();
        }
    }

    /**
     * The solver is a single shared resource, and the deletes under test now
     * refuse while it is held — so every fixture waits for it, including the
     * cleanup, which would otherwise throw on a job another class left running.
     */
    private void attendreSolveurLibre() {
        for (int essai = 0; essai < 240 && solverJobs.findActive().isPresent(); essai++) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException interrompu) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertThat(solverJobs.findActive()).isEmpty();
    }

    private List<PosteAffectation> postesForStand(String standId) {
        return persistence.loadPersistedPlanning().getPostes().stream()
                .filter(poste -> poste.getStand() != null && standId.equals(poste.getStand().getId()))
                .toList();
    }

    private List<PosteAffectation> postesForAnimateur(String animateurId) {
        return persistence.loadPersistedPlanning().getPostes().stream()
                .filter(poste -> poste.getAnimateur() != null && animateurId.equals(poste.getAnimateur().getId()))
                .toList();
    }

    /**
     * The plan goes first, and unconditionally: should a delete under test fail,
     * its seats would still reference the row and the cleanup would throw in
     * turn, reporting "Failed to delete SUP-S1" over the real cause — the trap
     * PR #281 walked into.
     */
    private void nettoyer() {
        attendreSolveurLibre();
        persistence.persist(new PlanningEvenement(JOUR, List.of(), List.of()));
        for (String id : List.of("SUP-S1", "SUP-S2", "SUP-S3", "SUP-S4", "SUP-S5", "SUP-S6", "SUP-S7", "SUP-S8",
                "SUP-S9")) {
            referenceData.deleteStand(id);
        }
        for (String id : List.of("SUP-A1", "SUP-A2", "SUP-A3", "SUP-A4", "SUP-A5", "SUP-A6", "SUP-A7", "SUP-A8",
                "SUP-A9")) {
            referenceData.deleteAnimateur(id);
        }
        referenceData.deleteCreneaux(List.of(9501L, 9502L, 9503L, 9504L, 9505L, 9506L));
    }

    private static Creneau creneau(long id) {
        return new Creneau(id, 1, JOUR, LocalTime.of(9, 0), LocalTime.of(12, 0));
    }

    private static Stand stand(String id) {
        return new Stand(id, "Stand " + id, Set.of(), 1, 1, false);
    }

    private static Animateur animateur(String id) {
        return new Animateur(id, "Prenom", "Nom " + id, LocalDate.of(1990, 1, 1), false);
    }

    private static PosteAffectation poste(String id, Stand stand, Creneau creneau, Animateur animateur) {
        PosteAffectation poste = new PosteAffectation(id, stand, creneau);
        poste.setAnimateur(animateur);
        return poste;
    }
}
