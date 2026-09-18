package dev.sylvain.planning.solver.constraints;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypeContrainteAdHoc;
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
}
