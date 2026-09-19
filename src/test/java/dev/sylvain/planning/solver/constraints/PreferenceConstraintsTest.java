package dev.sylvain.planning.solver.constraints;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import org.junit.jupiter.api.Test;

class PreferenceConstraintsTest extends ConstraintTestBase {

    private final Stand standStrat = standWithStrategy("STAND-STRAT");
    private final Stand standAutre = standWithStrategy("STAND-AUTRE");
    private final Creneau creneauMatin = matin("J1-MATIN", 1, D1);
    private final Creneau creneauAprem = afternoon("J1-AM", 1, D1);

    /* ----------------------- Renforts (issue #505) ------------------------ */

    @Test
    void aRenfortSomebodyTakesIsRewarded() {
        verify("pourvoirLesSiegesOptionnels")
                .given(posteOptionnel(standStrat, creneauMatin, referentMajeur("A1")))
                .rewardsWith(1);
    }

    /**
     * The whole point of a renfort: leaving it empty is not a violation, and
     * not a missed reward either — the score says nothing at all about it, so
     * a capacity nobody staffs never reads as a need nobody met.
     */
    @Test
    void anEmptyRenfortIsWorthNothingAndCostsNothing() {
        verify("pourvoirLesSiegesOptionnels")
                .given(new PosteAffectation("P-VIDE", standStrat, creneauMatin))
                .rewardsWith(0);
    }

    /** An ordinary seat is the job, not a bonus: filling it earns nothing here. */
    @Test
    void anOrdinarySeatEarnsNothingFromThisRule() {
        verify("pourvoirLesSiegesOptionnels")
                .given(poste(standStrat, creneauMatin, referentMajeur("A1")))
                .rewardsWith(0);
    }

    /** A renfort already worked is a constant no move can act on (ADR 0044). */
    @Test
    void aPastRenfortEarnsNothing() {
        PosteAffectation passe =
                posteOptionnel(standStrat, creneauMatin, referentMajeur("A1"));
        passe.setPasse(true);
        verify("pourvoirLesSiegesOptionnels").given(passe).rewardsWith(0);
    }

    @Test
    void referentSansDebutantEstPenaliseEnSoft() {
        verify("favoriserMixiteDesNiveaux")
                .given(poste(standStrat, creneauMatin, referentMajeur("A1")))
                .penalizesBy(1);
    }

    @Test
    void referentAccompagneDunDebutantNEstPasPenalise() {
        verify("favoriserMixiteDesNiveaux")
                .given(
                        poste(standStrat, creneauMatin, referentMajeur("A1")),
                        poste(standStrat, creneauMatin, mineurDebutant("M1")))
                .penalizesBy(0);
    }

    @Test
    void creneauxPeniblesEquilibresNEstPasPenalise() {
        // One exhausting poste each: perfectly fair split.
        Stand standEpuisant = standEpuisant("STAND-EPUISANT");
        verify("equilibrerCreneauxPenibles")
                .given(
                        poste(standEpuisant, creneauMatin, referentMajeur("A1")),
                        poste(standEpuisant, creneauAprem, majeurAutonome("A2")))
                .penalizesBy(0);
    }

    @Test
    void creneauxPeniblesDesequilibresEstPenalise() {
        // A1 carries three exhausting postes against one for A2: marked imbalance
        // within the pénible population itself (both must appear in it — a
        // non-pénible poste for A2 wouldn't enter the loadBalance stream at all).
        Stand standEpuisant = standEpuisant("STAND-EPUISANT");
        Animateur a1 = referentMajeur("A1");
        verify("equilibrerCreneauxPenibles")
                .given(
                        poste(standEpuisant, creneauMatin, a1),
                        poste(standEpuisant, creneauAprem, a1),
                        poste(standEpuisant, matin("J2-MATIN", 2, D2), a1),
                        poste(standEpuisant, afternoon("J2-AM", 2, D2), majeurAutonome("A2")))
                .penalizesByMoreThan(0);
    }

    @Test
    void standNiPeniblesNiPremiumNEstPasComptabiliseDansLequilibrage() {
        // Only ordinary stands: nothing "pénible" to balance, whatever the split.
        Animateur a1 = referentMajeur("A1");
        verify("equilibrerCreneauxPenibles")
                .given(
                        poste(standStrat, creneauMatin, a1),
                        poste(standStrat, creneauAprem, a1),
                        poste(standAutre, creneauMatin, majeurAutonome("A2")))
                .penalizesBy(0);
    }

    @Test
    void seulPolyvalentOccupeSurUnCreneauEstPenalise() {
        // The only ninja works this créneau: no polyvalent left to patch an absence.
        Animateur ninja = ninja("N1");
        verify("preserverBufferPolyvalents")
                .given(ninja, poste(standStrat, creneauMatin, ninja))
                .penalizesBy(1);
    }

    @Test
    void unPolyvalentLibreSuffitAConstituerLeBuffer() {
        Animateur occupe = ninja("N1");
        Animateur libre = ninja("N2");
        verify("preserverBufferPolyvalents")
                .given(occupe, libre, poste(standStrat, creneauMatin, occupe))
                .penalizesBy(0);
    }

    @Test
    void polyvalentSurPlusieursPostesDuMemeCreneauNestCompteQuUneFois() {
        // Two seats, one and the same ninja: still exactly one polyvalent busy,
        // so a second ninja keeps the buffer satisfied.
        Animateur occupe = ninja("N1");
        Animateur libre = ninja("N2");
        verify("preserverBufferPolyvalents")
                .given(occupe, libre, poste(standStrat, creneauMatin, occupe), poste(standAutre, creneauMatin, occupe))
                .penalizesBy(0);
    }

    @Test
    void sansTypologieNinjaAucunePenalite() {
        // No typologie flagged ninja in the referential: nobody is polyvalent and
        // the constraint must stay silent rather than penalising every créneau.
        Animateur a1 = referentMajeur("A1");
        verify("preserverBufferPolyvalents")
                .given(a1, poste(standStrat, creneauMatin, a1))
                .penalizesBy(0);
    }

    @Test
    void standPremiumEstComptabiliseDansLequilibrage() {
        Stand premium = standPremium("STAND-PREMIUM");
        Animateur a1 = referentMajeur("A1");
        verify("equilibrerCreneauxPenibles")
                .given(
                        poste(premium, creneauMatin, a1),
                        poste(premium, creneauAprem, a1),
                        poste(premium, matin("J2-MATIN", 2, D2), a1),
                        poste(premium, afternoon("J2-AM", 2, D2), majeurAutonome("A2")))
                .penalizesByMoreThan(0);
    }

    /* ------------------- counted, never reproached (ADR 0044) ------------------- */

    @Test
    void aReferentLeftWithoutABeginnerOnAPastLineIsHistory() {
        verify("favoriserMixiteDesNiveaux")
                .given(postePasse(standStrat, creneauMatin, referentMajeur("A1")))
                .penalizesBy(0);
        verify("favoriserMixiteDesNiveaux")
                .given(
                        postePasse(standStrat, creneauMatin, referentMajeur("A1")),
                        poste(standStrat, creneauMatin, majeurAutonome("A2")))
                .penalizesBy(1);
    }

    @Test
    void anImbalanceOfDemandingSeatsAllWorkedIsHistory() {
        Animateur a1 = referentMajeur("A1");
        Stand epuisant = standEpuisant("STAND-EPUISANT");
        verify("equilibrerCreneauxPenibles")
                .given(
                        postePasse(epuisant, creneauMatin, a1),
                        postePasse(epuisant, creneauAprem, a1),
                        postePasse(epuisant, matin("J2-MATIN", 2, D2), a1),
                        postePasse(epuisant, afternoon("J2-AM", 2, D2), majeurAutonome("A2")))
                .penalizesBy(0);
        verify("equilibrerCreneauxPenibles")
                .given(
                        postePasse(epuisant, creneauMatin, a1),
                        postePasse(epuisant, creneauAprem, a1),
                        postePasse(epuisant, matin("J2-MATIN", 2, D2), a1),
                        poste(epuisant, afternoon("J2-AM", 2, D2), majeurAutonome("A2")))
                .penalizesByMoreThan(0);
    }
}
