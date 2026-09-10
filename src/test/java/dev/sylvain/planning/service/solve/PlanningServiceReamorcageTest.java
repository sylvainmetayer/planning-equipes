package dev.sylvain.planning.service.solve;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypeContrainteAdHoc;
import org.junit.jupiter.api.Test;

/**
 * The warm start of issue #174, on the seats alone: seeded from the persisted
 * plan, <b>pinned nowhere</b> — the one line that separates it from the
 * incremental re-solve of #86, and the first test written for that reason.
 */
class PlanningServiceReamorcageTest {

    private static final LocalDate J1 = LocalDate.of(2026, 7, 8);

    private final Stand standA = new Stand("STAND-A", "Stand A", Set.of("STRATEGIE"), 2, 2, false);
    private final Stand standB = new Stand("STAND-B", "Stand B", Set.of("STRATEGIE"), 1, 1, false);
    private final Creneau matin = new Creneau(1L, 1, J1, LocalTime.of(9, 0), LocalTime.of(13, 0));
    private final Animateur alice = new Animateur("A1", "Alice", "Martin", LocalDate.of(2000, 1, 1), false);
    private final Animateur bob = new Animateur("A2", "Bob", "Durand", LocalDate.of(2000, 1, 1), false);
    private final List<Animateur> animateurs = List.of(alice, bob);

    private static String key(String standId, long creneauId) {
        return PlanningPersistenceService.standCreneauKey(standId, creneauId);
    }

    private static PosteAffectation poste(String id, Stand stand, Creneau creneau) {
        return new PosteAffectation(id, stand, creneau);
    }

    @Test
    void seedsEverySeatFromThePersistedPlanAndPinsNone() {
        List<PosteAffectation> postes = List.of(poste("p0", standA, matin), poste("p1", standA, matin),
                poste("p2", standB, matin));

        int[] bilan = ProblemBuilder.reamorcerDepuisAffectations(postes, animateurs,
                Map.of(key("STAND-A", 1L), List.of("A1", "A2"), key("STAND-B", 1L), List.of("A1")), List.of());

        assertThat(postes).extracting(PosteAffectation::getAnimateur).containsExactly(alice, bob, alice);
        assertThat(postes).noneMatch(PosteAffectation::isVerrouille);
        assertThat(bilan).containsExactly(3, 0);
    }

    @Test
    void leavesAPinnedSeatAsTheLocksLeftItAndKeepsCountingPositions() {
        List<PosteAffectation> postes = List.of(poste("p0", standA, matin), poste("p1", standA, matin));
        // The locks already seeded and pinned the first seat with Bob (not
        // Alice): a warm start must not overwrite it, and the second seat still
        // takes the second tenant.
        postes.get(0).setAnimateur(bob);
        postes.get(0).setVerrouille(true);

        int[] bilan = ProblemBuilder.reamorcerDepuisAffectations(postes, animateurs,
                Map.of(key("STAND-A", 1L), List.of("A1", "A2")), List.of());

        assertThat(postes.get(0).getAnimateur()).isEqualTo(bob);
        assertThat(postes.get(0).isVerrouille()).isTrue();
        assertThat(postes.get(1).getAnimateur()).isEqualTo(bob);
        assertThat(postes.get(1).isVerrouille()).isFalse();
        assertThat(bilan).containsExactly(1, 0);
    }

    @Test
    void startsEmptyWhereTheTenantIsGoneOrHasSinceDeclaredTheDayOff() {
        alice.setJoursIndisponibles(Set.of(J1));
        List<PosteAffectation> postes = List.of(poste("p0", standA, matin), poste("p1", standA, matin),
                poste("p2", standB, matin));

        int[] bilan = ProblemBuilder.reamorcerDepuisAffectations(postes, animateurs,
                Map.of(key("STAND-A", 1L), List.of("A1", "DISPARU"), key("STAND-B", 1L), List.of("A2")), List.of());

        assertThat(postes.get(0).getAnimateur()).isNull();
        assertThat(postes.get(1).getAnimateur()).isNull();
        assertThat(postes.get(2).getAnimateur()).isEqualTo(bob);
        assertThat(postes).noneMatch(PosteAffectation::isVerrouille);
        assertThat(bilan).containsExactly(1, 2);
    }

    @Test
    void startsEmptyWhereAForcedUnavailabilityNowCoversTheSeat() {
        ContrainteAdHoc indisponibilite = new ContrainteAdHoc("C1", TypeContrainteAdHoc.INDISPONIBILITE_FORCEE);
        indisponibilite.getAnimateursConcernes().add(alice);
        indisponibilite.setCreneau(matin);
        List<PosteAffectation> postes = List.of(poste("p0", standB, matin));

        int[] bilan = ProblemBuilder.reamorcerDepuisAffectations(postes, animateurs,
                Map.of(key("STAND-B", 1L), List.of("A1")), List.of(indisponibilite));

        assertThat(postes.get(0).getAnimateur()).isNull();
        assertThat(bilan).containsExactly(0, 1);
    }

    @Test
    void aSeatThePlanNeverStaffedStaysFree() {
        List<PosteAffectation> postes = List.of(poste("p0", standA, matin), poste("p1", standA, matin));

        int[] bilan = ProblemBuilder.reamorcerDepuisAffectations(postes, animateurs,
                Map.of(key("STAND-A", 1L), List.of("A1")), List.of());

        assertThat(postes.get(0).getAnimateur()).isEqualTo(alice);
        assertThat(postes.get(1).getAnimateur()).isNull();
        assertThat(bilan).containsExactly(1, 0);
    }
}
