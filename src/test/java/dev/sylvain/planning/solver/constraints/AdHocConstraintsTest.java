package dev.sylvain.planning.solver.constraints;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.ParametresQualite;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypeContrainteAdHoc;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class AdHocConstraintsTest extends ConstraintTestBase {

    private final Stand standStrat = standWithStrategy("STAND-STRAT");
    private final Creneau creneauMatin = matin("J1-MATIN", 1, D1);
    private final Creneau creneauAprem = afternoon("J1-AM", 1, D1);

    private static ContrainteAdHoc contrainte(
            String id, TypeContrainteAdHoc type, Creneau creneau, Stand stand, Animateur... animateurs) {
        ContrainteAdHoc c = new ContrainteAdHoc(id, type);
        c.setAnimateursConcernes(new java.util.ArrayList<>(List.of(animateurs)));
        c.setCreneau(creneau);
        c.setStand(stand);
        return c;
    }

    @Test
    void indisponibiliteForceeVioleeEstPenalisee() {
        Animateur a1 = referentMajeur("A1");
        verify("indisponibiliteForcee")
                .given(
                        a1,
                        poste(standStrat, creneauMatin, a1),
                        contrainte("C1", TypeContrainteAdHoc.INDISPONIBILITE_FORCEE, creneauMatin, null, a1))
                .penalizesBy(1);
    }

    @Test
    void indisponibiliteForceeSurUnAutreCreneauNEstPasPenalisee() {
        Animateur a1 = referentMajeur("A1");
        verify("indisponibiliteForcee")
                .given(
                        a1,
                        poste(standStrat, creneauMatin, a1),
                        contrainte("C1", TypeContrainteAdHoc.INDISPONIBILITE_FORCEE, creneauAprem, null, a1))
                .penalizesBy(0);
    }

    @Test
    void incompatibiliteEntreDeuxAnimateursSurMemeCreneauEstPenalisee() {
        Animateur a1 = referentMajeur("A1");
        Animateur a2 = majeurAutonome("A2");
        verify("incompatibiliteAdHoc")
                .given(
                        a1,
                        a2,
                        poste(standStrat, creneauMatin, a1),
                        poste(standWithStrategy("STAND-2"), creneauMatin, a2),
                        contrainte("C1", TypeContrainteAdHoc.INCOMPATIBILITE, null, null, a1, a2))
                .penalizesBy(1);
    }

    @Test
    void incompatibiliteSurDesCreneauxDifferentsNEstPasPenalisee() {
        Animateur a1 = referentMajeur("A1");
        Animateur a2 = majeurAutonome("A2");
        verify("incompatibiliteAdHoc")
                .given(
                        a1,
                        a2,
                        poste(standStrat, creneauMatin, a1),
                        poste(standStrat, creneauAprem, a2),
                        contrainte("C1", TypeContrainteAdHoc.INCOMPATIBILITE, null, null, a1, a2))
                .penalizesBy(0);
    }

    @Test
    void affectationForceeNonSatisfaiteEstPenalisee() {
        Animateur a1 = referentMajeur("A1");
        // No seat assigns A1 on the timeslot aimed at.
        verify("affectationForcee")
                .given(
                        a1,
                        poste(standStrat, creneauMatin, majeurAutonome("A2")),
                        contrainte("C1", TypeContrainteAdHoc.AFFECTATION_FORCEE, creneauMatin, null, a1))
                .penalizesBy(1);
    }

    @Test
    void affectationForceeSatisfaiteNEstPasPenalisee() {
        Animateur a1 = referentMajeur("A1");
        verify("affectationForcee")
                .given(
                        a1,
                        poste(standStrat, creneauMatin, a1),
                        contrainte("C1", TypeContrainteAdHoc.AFFECTATION_FORCEE, creneauMatin, null, a1))
                .penalizesBy(0);
    }

    @Test
    void affinitePaireCoAffecteeSurLeMemeStandEstRecompensee() {
        Animateur a1 = referentMajeur("A1");
        Animateur a2 = majeurAutonome("A2");
        verify("affiniteAdHoc")
                .given(
                        a1,
                        a2,
                        poste(standStrat, creneauMatin, a1),
                        poste(standStrat, creneauMatin, a2),
                        contrainte("C1", TypeContrainteAdHoc.AFFINITE, null, null, a1, a2))
                .rewardsWith(1);
    }

    @Test
    void affinitePaireSepareeSurDeuxStandsEstNeutre() {
        Animateur a1 = referentMajeur("A1");
        Animateur a2 = majeurAutonome("A2");
        verify("affiniteAdHoc")
                .given(
                        a1,
                        a2,
                        poste(standStrat, creneauMatin, a1),
                        poste(standWithStrategy("STAND-2"), creneauMatin, a2),
                        contrainte("C1", TypeContrainteAdHoc.AFFINITE, null, null, a1, a2))
                .rewardsWith(0);
    }

    @Test
    void affiniteAvecUnMembreQuiNeTravaillePasEstNeutre() {
        Animateur a1 = referentMajeur("A1");
        Animateur a2 = majeurAutonome("A2");
        // A2 holds no seat: neither reward nor penalty.
        verify("affiniteAdHoc")
                .given(
                        a1,
                        a2,
                        poste(standStrat, creneauMatin, a1),
                        contrainte("C1", TypeContrainteAdHoc.AFFINITE, null, null, a1, a2))
                .rewardsWith(0);
    }

    @Test
    void affiniteHorsDuPerimetreDeclareEstNeutre() {
        Animateur a1 = referentMajeur("A1");
        Animateur a2 = majeurAutonome("A2");
        // The pair is together in the morning, but the affinity only aims at the afternoon.
        verify("affiniteAdHoc")
                .given(
                        a1,
                        a2,
                        poste(standStrat, creneauMatin, a1),
                        poste(standStrat, creneauMatin, a2),
                        contrainte("C1", TypeContrainteAdHoc.AFFINITE, creneauAprem, null, a1, a2))
                .rewardsWith(0);
    }

    /* ------------------- counted, never reproached (ADR 0044) ------------------- */

    @Test
    void aForcedUnavailabilityBreachedOnAPastSeatIsHistory() {
        Animateur a1 = referentMajeur("A1");
        verify("indisponibiliteForcee")
                .given(
                        a1,
                        postePasse(standStrat, creneauMatin, a1),
                        contrainte("C1", TypeContrainteAdHoc.INDISPONIBILITE_FORCEE, creneauMatin, null, a1))
                .penalizesBy(0);
    }

    @Test
    void aForcedAssignmentOnATimeslotAlreadyWorkedWithoutItIsHistory() {
        Animateur a1 = referentMajeur("A1");
        Animateur a2 = majeurAutonome("A2");
        verify("affectationForcee")
                .given(
                        a1,
                        a2,
                        postePasse(standStrat, creneauMatin, a2),
                        contrainte("C1", TypeContrainteAdHoc.AFFECTATION_FORCEE, creneauMatin, null, a1))
                .penalizesBy(0);
        verify("affectationForcee")
                .given(
                        a1,
                        a2,
                        poste(standStrat, creneauMatin, a2),
                        contrainte("C1", TypeContrainteAdHoc.AFFECTATION_FORCEE, creneauMatin, null, a1))
                .penalizesBy(1);
    }

    // --- arriveeGroupee -------------------------------------------------------

    private static final ParametresQualite TOLERANCE_30 = new ParametresQualite();

    private static ContrainteAdHoc groupe(Animateur... membres) {
        return contrainte("G1", TypeContrainteAdHoc.ARRIVEE_GROUPEE, null, null, membres);
    }

    private static Creneau hours(String id, int jour, java.time.LocalDate date, int from, int fromMin, int to) {
        return creneau(id, jour, date, LocalTime.of(from, fromMin), LocalTime.of(to, 0));
    }

    @Test
    void aGroupWorkingTheSameDaysWithinTheToleranceCostsNothingEvenOnDifferentStands() {
        Animateur a1 = referentMajeur("A1");
        Animateur a2 = referentMajeur("A2");
        verify("arriveeGroupee")
                .given(
                        TOLERANCE_30,
                        groupe(a1, a2),
                        poste(standStrat, hours("G-A1", 1, D1, 9, 0, 17), a1),
                        poste(standWithStrategy("AUTRE"), hours("G-A2", 1, D1, 9, 20, 17), a2))
                .penalizesBy(0);
    }

    @Test
    void aMemberArrivingFortyFiveMinutesAfterTheOtherCostsTheFifteenBeyondTheTolerance() {
        Animateur a1 = referentMajeur("A1");
        Animateur a2 = referentMajeur("A2");
        verify("arriveeGroupee")
                .given(
                        TOLERANCE_30,
                        groupe(a1, a2),
                        poste(standStrat, hours("G-B1", 1, D1, 9, 0, 17), a1),
                        poste(standWithStrategy("AUTRE"), hours("G-B2", 1, D1, 9, 45, 17), a2))
                .penalizesBy(15);
    }

    @Test
    void aMemberWhoDoesNotWorkADayTheOtherWorksCostsTheFlatRate() {
        Animateur a1 = referentMajeur("A1");
        Animateur a2 = referentMajeur("A2");
        verify("arriveeGroupee")
                .given(
                        TOLERANCE_30,
                        groupe(a1, a2),
                        poste(standStrat, hours("G-C1", 1, D1, 9, 0, 17), a1),
                        poste(standStrat, hours("G-C2", 2, D2, 9, 0, 17), a1),
                        poste(standWithStrategy("AUTRE"), hours("G-C3", 1, D1, 9, 0, 17), a2))
                .penalizesBy(AdHocConstraints.FORFAIT_JOUR_SANS_COEQUIPIER_MINUTES);
    }

    @Test
    void aBreakInTheMiddleOfTheDayChangesNothing() {
        Animateur a1 = referentMajeur("A1");
        Animateur a2 = referentMajeur("A2");
        verify("arriveeGroupee")
                .given(
                        TOLERANCE_30,
                        groupe(a1, a2),
                        poste(standStrat, hours("G-D1", 1, D1, 9, 0, 12), a1),
                        poste(standStrat, hours("G-D2", 1, D1, 14, 0, 18), a1),
                        poste(standWithStrategy("AUTRE"), hours("G-D3", 1, D1, 9, 0, 18), a2))
                .penalizesBy(0);
    }

    @Test
    void aPastDayIsNoLongerCharged() {
        Animateur a1 = referentMajeur("A1");
        Animateur a2 = referentMajeur("A2");
        verify("arriveeGroupee")
                .given(
                        TOLERANCE_30,
                        groupe(a1, a2),
                        postePasse(standStrat, hours("G-E1", 1, D1, 9, 0, 17), a1),
                        postePasse(standWithStrategy("AUTRE"), hours("G-E2", 1, D1, 11, 0, 17), a2))
                .penalizesBy(0);
    }

    @Test
    void theToleranceIsASetting() {
        Animateur a1 = referentMajeur("A1");
        Animateur a2 = referentMajeur("A2");
        verify("arriveeGroupee")
                .given(
                        TOLERANCE_30.withToleranceArriveeGroupee(60),
                        groupe(a1, a2),
                        poste(standStrat, hours("G-F1", 1, D1, 9, 0, 17), a1),
                        poste(standWithStrategy("AUTRE"), hours("G-F2", 1, D1, 9, 45, 17), a2))
                .penalizesBy(0);
    }

    @Test
    void everyPairOfAThreeCarIsCharged() {
        Animateur a1 = referentMajeur("A1");
        Animateur a2 = referentMajeur("A2");
        Animateur a3 = referentMajeur("A3");
        // A3 arrives 45 minutes after the two others: pairs (A1, A3) and (A2, A3).
        verify("arriveeGroupee")
                .given(
                        TOLERANCE_30,
                        groupe(a1, a2, a3),
                        poste(standStrat, hours("G-H1", 1, D1, 9, 0, 17), a1),
                        poste(standWithStrategy("B"), hours("G-H2", 1, D1, 9, 0, 17), a2),
                        poste(standWithStrategy("C"), hours("G-H3", 1, D1, 9, 45, 17), a3))
                .penalizesBy(30);
    }
}
