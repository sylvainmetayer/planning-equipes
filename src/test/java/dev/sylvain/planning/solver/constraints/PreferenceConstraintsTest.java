package dev.sylvain.planning.solver.constraints;

import java.time.LocalTime;
import org.junit.jupiter.api.Test;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Stand;

class PreferenceConstraintsTest extends ConstraintTestBase {

    private final Stand standStrat = standStrategie("STAND-STRAT");
    private final Stand standAutre = standStrategie("STAND-AUTRE");
    private final Creneau creneauMatin = matin("J1-MATIN", 1, D1);
    private final Creneau creneauAprem = apresMidi("J1-AM", 1, D1);

    @Test
    void memeStandRepeteEstPenaliseEnSoft() {
        Animateur a1 = majeurReferent("A1");
        verify("favoriserRotationDesStands")
                .given(poste(standStrat, creneauMatin, a1),
                        poste(standStrat, creneauAprem, a1))
                .penalizesBy(1);
    }

    /**
     * Three postes on the same stand cost three pairs, not three points: the
     * penalty is the number of unordered pairs, {@code k(k-1)/2}. Locks the
     * closed-form count against the {@code forEachUniquePair} formulation it
     * replaces — with k=2 alone (the only case previously covered) the two
     * formulas are indistinguishable.
     */
    @Test
    void troisPostesSurLeMemeStandCoutentTroisPaires() {
        Animateur a1 = majeurReferent("A1");
        verify("favoriserRotationDesStands")
                .given(poste(standStrat, creneauMatin, a1),
                        poste(standStrat, creneauAprem, a1),
                        poste(standStrat, creneau("J1-SOIR", 1, D1, LocalTime.of(18, 30), LocalTime.of(22, 0)), a1))
                .penalizesBy(3);
    }

    @Test
    void rotationSurDeuxStandsDifferentsNEstPasPenalisee() {
        Animateur a1 = majeurReferent("A1");
        verify("favoriserRotationDesStands")
                .given(poste(standStrat, creneauMatin, a1),
                        poste(standAutre, creneauAprem, a1))
                .penalizesBy(0);
    }

    @Test
    void referentSansDebutantEstPenaliseEnSoft() {
        verify("favoriserMixiteDesNiveaux")
                .given(poste(standStrat, creneauMatin, majeurReferent("A1")))
                .penalizesBy(1);
    }

    @Test
    void referentAccompagneDunDebutantNEstPasPenalise() {
        verify("favoriserMixiteDesNiveaux")
                .given(poste(standStrat, creneauMatin, majeurReferent("A1")),
                        poste(standStrat, creneauMatin, mineurDebutant("M1")))
                .penalizesBy(0);
    }

    @Test
    void creneauxPeniblesEquilibresNEstPasPenalise() {
        // One exhausting poste each: perfectly fair split.
        Stand standEpuisant = standEpuisant("STAND-EPUISANT");
        verify("equilibrerCreneauxPenibles")
                .given(poste(standEpuisant, creneauMatin, majeurReferent("A1")),
                        poste(standEpuisant, creneauAprem, majeurAutonome("A2")))
                .penalizesBy(0);
    }

    @Test
    void creneauxPeniblesDesequilibresEstPenalise() {
        // A1 carries three exhausting postes against one for A2: marked imbalance
        // within the pénible population itself (both must appear in it — a
        // non-pénible poste for A2 wouldn't enter the loadBalance stream at all).
        Stand standEpuisant = standEpuisant("STAND-EPUISANT");
        Animateur a1 = majeurReferent("A1");
        verify("equilibrerCreneauxPenibles")
                .given(poste(standEpuisant, creneauMatin, a1),
                        poste(standEpuisant, creneauAprem, a1),
                        poste(standEpuisant, matin("J2-MATIN", 2, D2), a1),
                        poste(standEpuisant, apresMidi("J2-AM", 2, D2), majeurAutonome("A2")))
                .penalizesByMoreThan(0);
    }

    @Test
    void standNiPeniblesNiPremiumNEstPasComptabiliseDansLequilibrage() {
        // Only ordinary stands: nothing "pénible" to balance, whatever the split.
        Animateur a1 = majeurReferent("A1");
        verify("equilibrerCreneauxPenibles")
                .given(poste(standStrat, creneauMatin, a1),
                        poste(standStrat, creneauAprem, a1),
                        poste(standAutre, creneauMatin, majeurAutonome("A2")))
                .penalizesBy(0);
    }

    @Test
    void standPremiumEstComptabiliseDansLequilibrage() {
        Stand premium = standPremium("STAND-PREMIUM");
        Animateur a1 = majeurReferent("A1");
        verify("equilibrerCreneauxPenibles")
                .given(poste(premium, creneauMatin, a1),
                        poste(premium, creneauAprem, a1),
                        poste(premium, matin("J2-MATIN", 2, D2), a1),
                        poste(premium, apresMidi("J2-AM", 2, D2), majeurAutonome("A2")))
                .penalizesByMoreThan(0);
    }
}
