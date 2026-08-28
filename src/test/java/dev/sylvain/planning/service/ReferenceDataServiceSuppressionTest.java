package dev.sylvain.planning.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import dev.sylvain.planning.domain.Animateur;
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
 * at all. The seats go with the entity, in the same transaction, and the rest of
 * the plan survives.</p>
 */
@QuarkusTest
class ReferenceDataServiceSuppressionTest {

    @Inject
    ReferenceDataService referenceData;

    @Inject
    PlanningPersistenceService persistence;

    private static final LocalDate JOUR = LocalDate.of(2030, 7, 3);

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

    /** Same for an animateur: the seats they held go with them. */
    @Test
    void supprimerUnAnimateurEmporteLesPostesQuIlOccupait() {
        Creneau creneau = creneau(9502L);
        Stand stand = stand("SUP-S3");
        Animateur cible = animateur("SUP-A2");
        Animateur voisin = animateur("SUP-A3");
        try {
            persistence.persist(new PlanningEvenement(JOUR, List.of(cible, voisin),
                    List.of(poste("SUP-P3", stand, creneau, cible),
                            poste("SUP-P4", stand, creneau, voisin))));
            assertThat(postesForAnimateur("SUP-A2")).isNotEmpty();

            referenceData.deleteAnimateur("SUP-A2");

            assertThat(referenceData.listAnimateurs()).noneMatch(a -> "SUP-A2".equals(a.getId()));
            assertThat(postesForAnimateur("SUP-A2")).isEmpty();
            assertThat(postesForAnimateur("SUP-A3")).hasSize(1);
        } finally {
            nettoyer();
        }
    }

    /**
     * Bulk deletion is the same statement repeated — the frontend's
     * {@code removeMany} issues one {@code DELETE /api/stands/{id}} per row, one
     * transaction each — so a lot whose every member holds seats must go through
     * whole rather than stop on the first referenced one.
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
            stands.forEach(stand -> referenceData.deleteStand(stand.getId()));

            assertThat(referenceData.listAnimateurs()).noneMatch(a -> a.getId().startsWith("SUP-A"));
            assertThat(referenceData.listStands()).noneMatch(s -> s.getId().startsWith("SUP-S"));
            assertThat(persistence.countPersistedAssignments()).isZero();
        } finally {
            nettoyer();
        }
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
        persistence.persist(new PlanningEvenement(JOUR, List.of(), List.of()));
        for (String id : List.of("SUP-S1", "SUP-S2", "SUP-S3", "SUP-S4", "SUP-S5", "SUP-S6")) {
            referenceData.deleteStand(id);
        }
        for (String id : List.of("SUP-A1", "SUP-A2", "SUP-A3", "SUP-A4", "SUP-A5", "SUP-A6")) {
            referenceData.deleteAnimateur(id);
        }
        referenceData.deleteCreneaux(List.of(9501L, 9502L, 9503L));
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
