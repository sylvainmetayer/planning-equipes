package dev.sylvain.planning.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypeVerrouillage;
import dev.sylvain.planning.domain.VerrouillagePlanning;

/**
 * Pinning of the seats covered by a planning lock (issue #87), exercised on
 * {@code PlanningService.appliquerVerrouillages} directly: no database, no
 * Quarkus context, no solve.
 */
class PlanningServiceVerrouillageTest {

    private static final LocalDate J1 = LocalDate.of(2026, 7, 8);
    private static final LocalDate J2 = LocalDate.of(2026, 7, 9);

    private final Stand standA = new Stand("STAND-A", "Stand A", java.util.Set.of("STRATEGIE"), 2, 2, false);
    private final Stand standB = new Stand("STAND-B", "Stand B", java.util.Set.of("STRATEGIE"), 1, 1, false);
    private final Creneau matinJ1 = new Creneau(1L, 1, J1, LocalTime.of(9, 0), LocalTime.of(13, 0));
    private final Creneau matinJ2 = new Creneau(2L, 2, J2, LocalTime.of(9, 0), LocalTime.of(13, 0));
    private final Animateur alice = new Animateur("A1", "Alice", "A", LocalDate.of(2000, 1, 1), false);
    private final Animateur bob = new Animateur("A2", "Bob", "B", LocalDate.of(2000, 1, 1), false);

    private final List<Animateur> animateurs = List.of(alice, bob);

    private static PosteAffectation poste(String id, Stand stand, Creneau creneau) {
        return new PosteAffectation(id, stand, creneau);
    }

    private static VerrouillagePlanning verrou(TypeVerrouillage type) {
        return new VerrouillagePlanning("V1", type);
    }

    @Test
    void unVerrouillageJourFigeLesPostesDeCeJourAvecLeurAnimateurPersiste() {
        List<PosteAffectation> postes = List.of(
                poste("poste-0", standA, matinJ1),
                poste("poste-1", standA, matinJ2));
        VerrouillagePlanning verrouillage = verrou(TypeVerrouillage.JOUR);
        verrouillage.setJour(J1);

        PlanningService.appliquerVerrouillages(postes, animateurs, List.of(verrouillage), Map.of(
                PlanningPersistenceService.cleStandCreneau("STAND-A", 1L), List.of("A1"),
                PlanningPersistenceService.cleStandCreneau("STAND-A", 2L), List.of("A2")));

        assertThat(postes.get(0).isVerrouille()).isTrue();
        assertThat(postes.get(0).getAnimateur()).isEqualTo(alice);
        assertThat(postes.get(1).isVerrouille()).isFalse();
        assertThat(postes.get(1).getAnimateur()).isNull();
    }

    @Test
    void unVerrouillageStandFigeTousSesCreneaux() {
        List<PosteAffectation> postes = List.of(
                poste("poste-0", standA, matinJ1),
                poste("poste-1", standA, matinJ2),
                poste("poste-2", standB, matinJ1));
        VerrouillagePlanning verrouillage = verrou(TypeVerrouillage.STAND);
        verrouillage.setStandId("STAND-A");

        PlanningService.appliquerVerrouillages(postes, animateurs, List.of(verrouillage), Map.of(
                PlanningPersistenceService.cleStandCreneau("STAND-A", 1L), List.of("A1"),
                PlanningPersistenceService.cleStandCreneau("STAND-A", 2L), List.of("A2"),
                PlanningPersistenceService.cleStandCreneau("STAND-B", 1L), List.of("A2")));

        assertThat(postes.get(0).isVerrouille()).isTrue();
        assertThat(postes.get(1).isVerrouille()).isTrue();
        assertThat(postes.get(2).isVerrouille()).isFalse();
        assertThat(postes.get(2).getAnimateur()).isNull();
    }

    @Test
    void unVerrouillageCreneauNeFigeQueCeCreneau() {
        List<PosteAffectation> postes = List.of(
                poste("poste-0", standA, matinJ1),
                poste("poste-1", standA, matinJ2));
        VerrouillagePlanning verrouillage = verrou(TypeVerrouillage.CRENEAU);
        verrouillage.setCreneauId(2L);

        PlanningService.appliquerVerrouillages(postes, animateurs, List.of(verrouillage), Map.of(
                PlanningPersistenceService.cleStandCreneau("STAND-A", 1L), List.of("A1"),
                PlanningPersistenceService.cleStandCreneau("STAND-A", 2L), List.of("A2")));

        assertThat(postes.get(0).isVerrouille()).isFalse();
        assertThat(postes.get(1).isVerrouille()).isTrue();
        assertThat(postes.get(1).getAnimateur()).isEqualTo(bob);
    }

    /**
     * The seat frozen by an ANIMATEUR lock is the one that animateur held, not
     * the first seat of the stand × créneau: seats are re-seeded in persisted
     * order before the locks are evaluated.
     */
    @Test
    void unVerrouillageAnimateurFigeLeSiegeQueCetAnimateurTenait() {
        List<PosteAffectation> postes = List.of(
                poste("poste-0", standA, matinJ1),
                poste("poste-1", standA, matinJ1));
        VerrouillagePlanning verrouillage = verrou(TypeVerrouillage.ANIMATEUR);
        verrouillage.setAnimateurId("A2");

        PlanningService.appliquerVerrouillages(postes, animateurs, List.of(verrouillage), Map.of(
                PlanningPersistenceService.cleStandCreneau("STAND-A", 1L), List.of("A1", "A2")));

        assertThat(postes.get(0).isVerrouille()).isFalse();
        assertThat(postes.get(0).getAnimateur()).isNull();
        assertThat(postes.get(1).isVerrouille()).isTrue();
        assertThat(postes.get(1).getAnimateur()).isEqualTo(bob);
    }

    /**
     * The ANIMATEUR_CRENEAU lock (issue #165, posé par un échange validé) only
     * freezes that animateur's seat on that créneau: their seats on other
     * créneaux stay free for the next solve.
     */
    @Test
    void unVerrouillageAnimateurCreneauNeFigeQueCeSiegeLa() {
        List<PosteAffectation> postes = List.of(
                poste("poste-0", standA, matinJ1),
                poste("poste-1", standA, matinJ2));
        VerrouillagePlanning verrouillage = verrou(TypeVerrouillage.ANIMATEUR_CRENEAU);
        verrouillage.setAnimateurId("A1");
        verrouillage.setCreneauId(1L);

        PlanningService.appliquerVerrouillages(postes, animateurs, List.of(verrouillage), Map.of(
                PlanningPersistenceService.cleStandCreneau("STAND-A", 1L), List.of("A1"),
                PlanningPersistenceService.cleStandCreneau("STAND-A", 2L), List.of("A1")));

        assertThat(postes.get(0).isVerrouille()).isTrue();
        assertThat(postes.get(0).getAnimateur()).isEqualTo(alice);
        assertThat(postes.get(1).isVerrouille()).isFalse();
        assertThat(postes.get(1).getAnimateur()).isNull();
    }

    /** On a two-seat stand, the seat frozen is the one the locked animateur held. */
    @Test
    void unVerrouillageAnimateurCreneauViseLeBonSiegeDuStand() {
        List<PosteAffectation> postes = List.of(
                poste("poste-0", standA, matinJ1),
                poste("poste-1", standA, matinJ1));
        VerrouillagePlanning verrouillage = verrou(TypeVerrouillage.ANIMATEUR_CRENEAU);
        verrouillage.setAnimateurId("A2");
        verrouillage.setCreneauId(1L);

        PlanningService.appliquerVerrouillages(postes, animateurs, List.of(verrouillage), Map.of(
                PlanningPersistenceService.cleStandCreneau("STAND-A", 1L), List.of("A1", "A2")));

        assertThat(postes.get(0).isVerrouille()).isFalse();
        assertThat(postes.get(0).getAnimateur()).isNull();
        assertThat(postes.get(1).isVerrouille()).isTrue();
        assertThat(postes.get(1).getAnimateur()).isEqualTo(bob);
    }

    /** A hole is never frozen: pinning it would make it permanently unfillable. */
    @Test
    void unSiegeNonPourvuNEstJamaisFige() {
        List<PosteAffectation> postes = List.of(
                poste("poste-0", standA, matinJ1),
                poste("poste-1", standA, matinJ1));
        VerrouillagePlanning verrouillage = verrou(TypeVerrouillage.JOUR);
        verrouillage.setJour(J1);

        // Only one of the two seats was staffed by the last persisted solve.
        PlanningService.appliquerVerrouillages(postes, animateurs, List.of(verrouillage),
                Map.of(PlanningPersistenceService.cleStandCreneau("STAND-A", 1L), List.of("A1")));

        assertThat(postes.get(0).isVerrouille()).isTrue();
        assertThat(postes.get(1).isVerrouille()).isFalse();
        assertThat(postes.get(1).getAnimateur()).isNull();
    }

    @Test
    void sansVerrouillageAucunPosteNEstPreAffecte() {
        List<PosteAffectation> postes = List.of(poste("poste-0", standA, matinJ1));

        PlanningService.appliquerVerrouillages(postes, animateurs, List.of(),
                Map.of(PlanningPersistenceService.cleStandCreneau("STAND-A", 1L), List.of("A1")));

        assertThat(postes.get(0).isVerrouille()).isFalse();
        assertThat(postes.get(0).getAnimateur()).isNull();
    }

    /** A lock recorded before anything has been solved freezes nothing. */
    @Test
    void sansPlanningPersisteAucunPosteNEstFige() {
        List<PosteAffectation> postes = List.of(poste("poste-0", standA, matinJ1));
        VerrouillagePlanning verrouillage = verrou(TypeVerrouillage.JOUR);
        verrouillage.setJour(J1);

        PlanningService.appliquerVerrouillages(postes, animateurs, List.of(verrouillage), Map.of());

        assertThat(postes.get(0).isVerrouille()).isFalse();
        assertThat(postes.get(0).getAnimateur()).isNull();
    }

    /**
     * Warm start (issue #86): unlike the lock path, the seeded seats are kept
     * as movable starting points, not cleared — the whole point of re-solving
     * from a group's last snapshot instead of from scratch.
     */
    @Test
    void leWarmStartConserveLesSeedsNonVerrouillesSansLesFiger() {
        List<PosteAffectation> postes = List.of(
                poste("poste-0", standA, matinJ1),
                poste("poste-1", standA, matinJ1));

        PlanningService.seedDepuisAffectations(postes, animateurs, List.of(),
                Map.of(PlanningPersistenceService.cleStandCreneau("STAND-A", 1L), List.of("A1", "A2")), true);

        assertThat(postes.get(0).getAnimateur()).isEqualTo(alice);
        assertThat(postes.get(0).isVerrouille()).isFalse();
        assertThat(postes.get(1).getAnimateur()).isEqualTo(bob);
        assertThat(postes.get(1).isVerrouille()).isFalse();
    }

    /** A seeded seat covered by a lock is pinned, warm start or not. */
    @Test
    void leWarmStartFigeQuandMemeLesPostesCouvertsParUnVerrouillage() {
        List<PosteAffectation> postes = List.of(
                poste("poste-0", standA, matinJ1),
                poste("poste-1", standA, matinJ2));
        VerrouillagePlanning verrouillage = verrou(TypeVerrouillage.JOUR);
        verrouillage.setJour(J1);

        PlanningService.seedDepuisAffectations(postes, animateurs, List.of(verrouillage), Map.of(
                PlanningPersistenceService.cleStandCreneau("STAND-A", 1L), List.of("A1"),
                PlanningPersistenceService.cleStandCreneau("STAND-A", 2L), List.of("A2")), true);

        assertThat(postes.get(0).isVerrouille()).isTrue();
        assertThat(postes.get(0).getAnimateur()).isEqualTo(alice);
        assertThat(postes.get(1).isVerrouille()).isFalse();
        assertThat(postes.get(1).getAnimateur()).isEqualTo(bob);
    }

    /**
     * An animateur id the referential no longer knows leaves its seat empty: a
     * stale seed is a degraded starting point, never an error (the missing-ref
     * strictness of a snapshot <i>restore</i> does not apply to a seed).
     */
    @Test
    void unSeedDontLAnimateurADisparuLaisseLePosteVide() {
        List<PosteAffectation> postes = List.of(poste("poste-0", standA, matinJ1));

        PlanningService.seedDepuisAffectations(postes, animateurs, List.of(),
                Map.of(PlanningPersistenceService.cleStandCreneau("STAND-A", 1L), List.of("DISPARU")), true);

        assertThat(postes.get(0).getAnimateur()).isNull();
        assertThat(postes.get(0).isVerrouille()).isFalse();
    }
}
