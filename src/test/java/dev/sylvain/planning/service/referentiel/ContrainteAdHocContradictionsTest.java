package dev.sylvain.planning.service.referentiel;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypeContrainteAdHoc;
import dev.sylvain.planning.service.referentiel.ContrainteAdHocContradictions.Contradiction;
import dev.sylvain.planning.service.referentiel.ContrainteAdHocContradictions.TypeContradiction;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The four impossible combinations of hand-entered exceptions (issue #84), and
 * — just as important — the near-misses that must go through: a combination
 * refused by mistake costs the user an exception they were entitled to, with
 * no way around it.
 */
class ContrainteAdHocContradictionsTest {

    private static final Creneau MATIN = creneau(1, LocalTime.of(10, 0), LocalTime.of(14, 0));
    private static final Creneau CHEVAUCHANT = creneau(2, LocalTime.of(12, 0), LocalTime.of(16, 0));
    private static final Creneau APRES = creneau(3, LocalTime.of(14, 0), LocalTime.of(18, 0));
    private static final Creneau SOIREE = creneau(4, LocalTime.of(22, 0), LocalTime.of(2, 0));
    private static final List<Creneau> CRENEAUX = List.of(MATIN, CHEVAUCHANT, APRES, SOIREE);

    /* --------------- Rule 1: the same pair, incompatible and preferred --------------- */

    @Test
    void anAffiniteOnAnAlreadyIncompatiblePairIsRefused() {
        ContrainteAdHoc incompatibilite = pair("C1", TypeContrainteAdHoc.INCOMPATIBILITE, "A1", "A2");
        ContrainteAdHoc affinite = pair("C2", TypeContrainteAdHoc.AFFINITE, "A1", "A2");

        assertThat(detect(affinite, incompatibilite)).singleElement().satisfies(contradiction -> {
            assertThat(contradiction.type()).isEqualTo(TypeContradiction.PAIRE_INCOMPATIBLE_ET_AFFINE);
            assertThat(contradiction.contrainteIds()).containsExactly("C1", "C2");
            assertThat(contradiction.message()).contains("C1").contains("incompatible");
        });
    }

    @Test
    void theSamePairIsSeenWhicheverWayRoundItIsTyped() {
        ContrainteAdHoc affinite = pair("C1", TypeContrainteAdHoc.AFFINITE, "A1", "A2");
        ContrainteAdHoc incompatibilite = pair("C2", TypeContrainteAdHoc.INCOMPATIBILITE, "A2", "A1");

        assertThat(detect(incompatibilite, affinite)).hasSize(1);
    }

    @Test
    void anotherPairIsAccepted() {
        ContrainteAdHoc incompatibilite = pair("C1", TypeContrainteAdHoc.INCOMPATIBILITE, "A1", "A2");
        ContrainteAdHoc affinite = pair("C2", TypeContrainteAdHoc.AFFINITE, "A1", "A3");

        assertThat(detect(affinite, incompatibilite)).isEmpty();
    }

    @Test
    void savingUnderTheSameIdReplacesTheConflictingVersion() {
        ContrainteAdHoc incompatibilite = pair("C1", TypeContrainteAdHoc.INCOMPATIBILITE, "A1", "A2");
        ContrainteAdHoc affinite = pair("C1", TypeContrainteAdHoc.AFFINITE, "A1", "A2");

        assertThat(detect(affinite, incompatibilite)).isEmpty();
    }

    /* --------------- Rule 2: forced onto a scope one is forced out of --------------- */

    @Test
    void aForcedSeatOnAnUnavailableSlotIsRefused() {
        ContrainteAdHoc indisponibilite =
                scoped("C1", TypeContrainteAdHoc.INDISPONIBILITE_FORCEE, MATIN, "STAND-A", "A1");
        ContrainteAdHoc forcee = scoped("C2", TypeContrainteAdHoc.AFFECTATION_FORCEE, MATIN, "STAND-A", "A1");

        assertThat(detect(forcee, indisponibilite)).singleElement().satisfies(contradiction -> {
            assertThat(contradiction.type()).isEqualTo(TypeContradiction.AFFECTATION_FORCEE_SUR_INDISPONIBILITE);
            assertThat(contradiction.contrainteIds()).containsExactly("C1", "C2");
            assertThat(contradiction.message())
                    .contains("C1")
                    .contains("C2")
                    .contains("A1")
                    .contains("2026-08-01 10:00-14:00");
        });
    }

    @Test
    void anUnavailabilityCoveringTheWholeEventCoversEveryForcedSeat() {
        ContrainteAdHoc indisponibilite = scoped("C1", TypeContrainteAdHoc.INDISPONIBILITE_FORCEE, null, null, "A1");
        ContrainteAdHoc forcee = scoped("C2", TypeContrainteAdHoc.AFFECTATION_FORCEE, MATIN, "STAND-A", "A1");

        assertThat(detect(forcee, indisponibilite)).hasSize(1);
    }

    @Test
    void theOrderTheTwoAreEnteredInDoesNotMatter() {
        ContrainteAdHoc forcee = scoped("C1", TypeContrainteAdHoc.AFFECTATION_FORCEE, MATIN, null, "A1");
        ContrainteAdHoc indisponibilite = scoped("C2", TypeContrainteAdHoc.INDISPONIBILITE_FORCEE, MATIN, null, "A1");

        assertThat(detect(indisponibilite, forcee)).hasSize(1);
    }

    @Test
    void anUnavailabilityNarrowerThanTheForcedSeatLeavesAWayOut() {
        // The forced seat may land on any stand of the créneau; only STAND-A is
        // closed to A1, so the solver still has somewhere to put them.
        ContrainteAdHoc indisponibilite =
                scoped("C1", TypeContrainteAdHoc.INDISPONIBILITE_FORCEE, MATIN, "STAND-A", "A1");
        ContrainteAdHoc forcee = scoped("C2", TypeContrainteAdHoc.AFFECTATION_FORCEE, MATIN, null, "A1");

        assertThat(detect(forcee, indisponibilite)).isEmpty();
    }

    @Test
    void aForcedSeatSatisfiableByASecondAnimateurIsAccepted() {
        // AFFECTATION_FORCEE is satisfied by either animateur it names, so an
        // unavailability covering only one of them settles nothing.
        ContrainteAdHoc indisponibilite = scoped("C1", TypeContrainteAdHoc.INDISPONIBILITE_FORCEE, MATIN, null, "A1");
        ContrainteAdHoc forcee = scoped("C2", TypeContrainteAdHoc.AFFECTATION_FORCEE, MATIN, null, "A1", "A2");

        assertThat(detect(forcee, indisponibilite)).isEmpty();
    }

    @Test
    void anUnavailabilityCoveringBothCandidatesIsRefused() {
        ContrainteAdHoc indisponibilite =
                scoped("C1", TypeContrainteAdHoc.INDISPONIBILITE_FORCEE, MATIN, null, "A1", "A2");
        ContrainteAdHoc forcee = scoped("C2", TypeContrainteAdHoc.AFFECTATION_FORCEE, MATIN, null, "A1", "A2");

        assertThat(detect(forcee, indisponibilite)).hasSize(1);
    }

    @Test
    void aForcedSeatNamingNobodyIsLeftToTheSolver() {
        ContrainteAdHoc indisponibilite = scoped("C1", TypeContrainteAdHoc.INDISPONIBILITE_FORCEE, MATIN, null, "A1");
        ContrainteAdHoc forcee = scoped("C2", TypeContrainteAdHoc.AFFECTATION_FORCEE, MATIN, null);

        assertThat(detect(forcee, indisponibilite)).isEmpty();
    }

    /* --------------------- Rule 3: two simultaneous forced seats --------------------- */

    @Test
    void twoForcedSeatsOnOverlappingCreneauxAreRefused() {
        ContrainteAdHoc premiere = scoped("C1", TypeContrainteAdHoc.AFFECTATION_FORCEE, MATIN, null, "A1");
        ContrainteAdHoc seconde = scoped("C2", TypeContrainteAdHoc.AFFECTATION_FORCEE, CHEVAUCHANT, null, "A1");

        assertThat(detect(seconde, premiere)).singleElement().satisfies(contradiction -> {
            assertThat(contradiction.type()).isEqualTo(TypeContradiction.AFFECTATIONS_FORCEES_SIMULTANEES);
            assertThat(contradiction.contrainteIds()).containsExactly("C1", "C2");
            assertThat(contradiction.message()).contains("C1").contains("C2").contains("A1");
        });
    }

    @Test
    void twoForcedSeatsOnTheSameCreneauButTwoStandsAreRefused() {
        ContrainteAdHoc premiere = scoped("C1", TypeContrainteAdHoc.AFFECTATION_FORCEE, MATIN, "STAND-A", "A1");
        ContrainteAdHoc seconde = scoped("C2", TypeContrainteAdHoc.AFFECTATION_FORCEE, MATIN, "STAND-B", "A1");

        assertThat(detect(seconde, premiere)).hasSize(1);
    }

    @Test
    void oneSeatCanSatisfyTwoForcedAssignmentsOfTheSameCreneau() {
        // "A1 works this créneau" and "A1 works this créneau on STAND-A" are
        // both settled by a single seat on STAND-A.
        ContrainteAdHoc large = scoped("C1", TypeContrainteAdHoc.AFFECTATION_FORCEE, MATIN, null, "A1");
        ContrainteAdHoc precise = scoped("C2", TypeContrainteAdHoc.AFFECTATION_FORCEE, MATIN, "STAND-A", "A1");

        assertThat(detect(precise, large)).isEmpty();
    }

    @Test
    void twoForcedSeatsOnConsecutiveCreneauxAreAccepted() {
        ContrainteAdHoc premiere = scoped("C1", TypeContrainteAdHoc.AFFECTATION_FORCEE, MATIN, null, "A1");
        ContrainteAdHoc seconde = scoped("C2", TypeContrainteAdHoc.AFFECTATION_FORCEE, APRES, null, "A1");

        assertThat(detect(seconde, premiere)).isEmpty();
    }

    @Test
    void aCreneauCrossingMidnightIsComparedOnTheDayItEndsOn() {
        // 22:00-02:00 runs into the next day, so it clashes with a 00:00-04:00
        // slot dated the day after — and not with the 10:00-14:00 of its own day.
        Creneau lendemainMatin = creneau(5, LocalDate.of(2026, 8, 2), LocalTime.of(0, 0), LocalTime.of(4, 0));
        List<Creneau> creneaux = new ArrayList<>(CRENEAUX);
        creneaux.add(lendemainMatin);
        ContrainteAdHoc soiree = scoped("C1", TypeContrainteAdHoc.AFFECTATION_FORCEE, SOIREE, null, "A1");
        ContrainteAdHoc lendemain = scoped("C2", TypeContrainteAdHoc.AFFECTATION_FORCEE, lendemainMatin, null, "A1");

        assertThat(ContrainteAdHocContradictions.detect(lendemain, List.of(soiree), creneaux))
                .hasSize(1);
        assertThat(ContrainteAdHocContradictions.detect(
                        scoped("C3", TypeContrainteAdHoc.AFFECTATION_FORCEE, MATIN, null, "A1"),
                        List.of(soiree),
                        creneaux))
                .isEmpty();
    }

    @Test
    void twoForcedSeatsOnTwoDifferentAnimateursAreAccepted() {
        ContrainteAdHoc premiere = scoped("C1", TypeContrainteAdHoc.AFFECTATION_FORCEE, MATIN, null, "A1");
        ContrainteAdHoc seconde = scoped("C2", TypeContrainteAdHoc.AFFECTATION_FORCEE, CHEVAUCHANT, null, "A2");

        assertThat(detect(seconde, premiere)).isEmpty();
    }

    @Test
    void aForcedSeatNamingTwoAnimateursCanBeSpreadOverTheTwoCreneaux() {
        ContrainteAdHoc premiere = scoped("C1", TypeContrainteAdHoc.AFFECTATION_FORCEE, MATIN, null, "A1", "A2");
        ContrainteAdHoc seconde = scoped("C2", TypeContrainteAdHoc.AFFECTATION_FORCEE, CHEVAUCHANT, null, "A1", "A2");

        assertThat(detect(seconde, premiere)).isEmpty();
    }

    @Test
    void anUnknownCreneauCannotBeComparedAndIsLeftAlone() {
        Creneau inconnu = new Creneau();
        inconnu.setId(99L);
        ContrainteAdHoc premiere = scoped("C1", TypeContrainteAdHoc.AFFECTATION_FORCEE, MATIN, null, "A1");
        ContrainteAdHoc seconde = scoped("C2", TypeContrainteAdHoc.AFFECTATION_FORCEE, inconnu, null, "A1");

        assertThat(detect(seconde, premiere)).isEmpty();
    }

    /* -------------- Rule 4: two forced seats of an incompatible pair -------------- */

    @Test
    void forcingAnIncompatiblePairOntoOneCreneauIsRefused() {
        ContrainteAdHoc premiere = scoped("C1", TypeContrainteAdHoc.AFFECTATION_FORCEE, MATIN, "STAND-A", "A1");
        ContrainteAdHoc seconde = scoped("C2", TypeContrainteAdHoc.AFFECTATION_FORCEE, MATIN, "STAND-B", "A2");
        ContrainteAdHoc incompatibilite = pair("C3", TypeContrainteAdHoc.INCOMPATIBILITE, "A1", "A2");

        assertThat(detect(incompatibilite, premiere, seconde)).singleElement().satisfies(contradiction -> {
            assertThat(contradiction.type()).isEqualTo(TypeContradiction.AFFECTATIONS_FORCEES_INCOMPATIBLES);
            assertThat(contradiction.contrainteIds()).containsExactly("C1", "C2", "C3");
            assertThat(contradiction.message()).contains("C1").contains("C2").contains("C3");
        });
    }

    @Test
    void theSecondForcedSeatIsRefusedWhenTheIncompatibilityCameFirst() {
        ContrainteAdHoc incompatibilite = pair("C1", TypeContrainteAdHoc.INCOMPATIBILITE, "A1", "A2");
        ContrainteAdHoc premiere = scoped("C2", TypeContrainteAdHoc.AFFECTATION_FORCEE, MATIN, null, "A1");
        ContrainteAdHoc seconde = scoped("C3", TypeContrainteAdHoc.AFFECTATION_FORCEE, MATIN, null, "A2");

        assertThat(detect(seconde, incompatibilite, premiere)).hasSize(1);
    }

    @Test
    void anIncompatibilityRestrictedToOneStandDoesNotCoverASeatFreeToMoveElsewhere() {
        ContrainteAdHoc premiere = scoped("C1", TypeContrainteAdHoc.AFFECTATION_FORCEE, MATIN, "STAND-A", "A1");
        ContrainteAdHoc seconde = scoped("C2", TypeContrainteAdHoc.AFFECTATION_FORCEE, MATIN, null, "A2");
        ContrainteAdHoc incompatibilite = scopedPair("C3", MATIN, "STAND-A", "A1", "A2");

        assertThat(detect(incompatibilite, premiere, seconde)).isEmpty();
    }

    @Test
    void anIncompatibilityRestrictedToTheStandBothSeatsSitOnIsRefused() {
        ContrainteAdHoc premiere = scoped("C1", TypeContrainteAdHoc.AFFECTATION_FORCEE, MATIN, "STAND-A", "A1");
        ContrainteAdHoc seconde = scoped("C2", TypeContrainteAdHoc.AFFECTATION_FORCEE, MATIN, "STAND-A", "A2");
        ContrainteAdHoc incompatibilite = scopedPair("C3", MATIN, "STAND-A", "A1", "A2");

        assertThat(detect(incompatibilite, premiere, seconde)).hasSize(1);
    }

    @Test
    void anIncompatiblePairForcedOntoTwoDifferentCreneauxIsAccepted() {
        // The incompatibility rule joins on the créneau: two seats on two
        // créneaux never meet, overlapping hours or not.
        ContrainteAdHoc premiere = scoped("C1", TypeContrainteAdHoc.AFFECTATION_FORCEE, MATIN, null, "A1");
        ContrainteAdHoc seconde = scoped("C2", TypeContrainteAdHoc.AFFECTATION_FORCEE, CHEVAUCHANT, null, "A2");
        ContrainteAdHoc incompatibilite = pair("C3", TypeContrainteAdHoc.INCOMPATIBILITE, "A1", "A2");

        assertThat(detect(incompatibilite, premiere, seconde)).isEmpty();
    }

    @Test
    void anIncompatibilityOnAnotherCreneauLeavesTheForcedSeatsAlone() {
        ContrainteAdHoc premiere = scoped("C1", TypeContrainteAdHoc.AFFECTATION_FORCEE, MATIN, null, "A1");
        ContrainteAdHoc seconde = scoped("C2", TypeContrainteAdHoc.AFFECTATION_FORCEE, MATIN, null, "A2");
        ContrainteAdHoc incompatibilite = scopedPair("C3", APRES, null, "A1", "A2");

        assertThat(detect(incompatibilite, premiere, seconde)).isEmpty();
    }

    /* ----------------------------- The set as a whole ----------------------------- */

    @Test
    void everyContradictionOfASetIsReportedOnceEach() {
        List<ContrainteAdHoc> contraintes = List.of(
                pair("C1", TypeContrainteAdHoc.INCOMPATIBILITE, "A1", "A2"),
                pair("C2", TypeContrainteAdHoc.AFFINITE, "A1", "A2"),
                scoped("C3", TypeContrainteAdHoc.INDISPONIBILITE_FORCEE, MATIN, null, "A3"),
                scoped("C4", TypeContrainteAdHoc.AFFECTATION_FORCEE, MATIN, null, "A3"),
                scoped("C5", TypeContrainteAdHoc.AFFECTATION_FORCEE, APRES, null, "A4"));

        assertThat(ContrainteAdHocContradictions.detectAll(contraintes, CRENEAUX))
                .extracting(Contradiction::type)
                .containsExactly(
                        TypeContradiction.PAIRE_INCOMPATIBLE_ET_AFFINE,
                        TypeContradiction.AFFECTATION_FORCEE_SUR_INDISPONIBILITE);
    }

    @Test
    void aConsistentSetHoldsNoContradiction() {
        List<ContrainteAdHoc> contraintes = List.of(
                pair("C1", TypeContrainteAdHoc.INCOMPATIBILITE, "A1", "A2"),
                scoped("C2", TypeContrainteAdHoc.AFFECTATION_FORCEE, MATIN, "STAND-A", "A1"),
                scoped("C3", TypeContrainteAdHoc.AFFECTATION_FORCEE, APRES, "STAND-B", "A2"),
                scoped("C4", TypeContrainteAdHoc.INDISPONIBILITE_FORCEE, APRES, null, "A1"));

        assertThat(ContrainteAdHocContradictions.detectAll(contraintes, CRENEAUX))
                .isEmpty();
    }

    @Test
    void nullsAndUnidentifiedConstraintsAreIgnored() {
        List<ContrainteAdHoc> contraintes = new ArrayList<>();
        contraintes.add(null);
        contraintes.add(new ContrainteAdHoc(null, TypeContrainteAdHoc.INCOMPATIBILITE));
        contraintes.add(new ContrainteAdHoc("C1", null));

        assertThat(ContrainteAdHocContradictions.detectAll(contraintes, CRENEAUX))
                .isEmpty();
        assertThat(ContrainteAdHocContradictions.detectAll(null, null)).isEmpty();
        assertThat(ContrainteAdHocContradictions.detect(null, contraintes, CRENEAUX))
                .isEmpty();
    }

    private static List<Contradiction> detect(ContrainteAdHoc candidate, ContrainteAdHoc... others) {
        return ContrainteAdHocContradictions.detect(candidate, List.of(others), CRENEAUX);
    }

    private static ContrainteAdHoc pair(String id, TypeContrainteAdHoc type, String premier, String second) {
        return scoped(id, type, null, null, premier, second);
    }

    private static ContrainteAdHoc scopedPair(
            String id, Creneau creneau, String standId, String premier, String second) {
        return scoped(id, TypeContrainteAdHoc.INCOMPATIBILITE, creneau, standId, premier, second);
    }

    private static ContrainteAdHoc scoped(
            String id, TypeContrainteAdHoc type, Creneau creneau, String standId, String... animateurs) {
        ContrainteAdHoc contrainte = new ContrainteAdHoc(id, type);
        contrainte.setCreneau(creneau);
        if (standId != null) {
            Stand stand = new Stand();
            stand.setId(standId);
            contrainte.setStand(stand);
        }
        List<Animateur> cibles = new ArrayList<>();
        for (String animateurId : animateurs) {
            Animateur animateur = new Animateur();
            animateur.setId(animateurId);
            cibles.add(animateur);
        }
        contrainte.setAnimateursConcernes(cibles);
        return contrainte;
    }

    private static Creneau creneau(long id, LocalTime debut, LocalTime fin) {
        return creneau(id, LocalDate.of(2026, 8, 1), debut, fin);
    }

    private static Creneau creneau(long id, LocalDate date, LocalTime debut, LocalTime fin) {
        return new Creneau(id, 1, date, debut, fin);
    }
}
