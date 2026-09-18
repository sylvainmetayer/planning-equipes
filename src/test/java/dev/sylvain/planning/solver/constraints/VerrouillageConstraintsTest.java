package dev.sylvain.planning.solver.constraints;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypeVerrouillage;
import dev.sylvain.planning.domain.VerrouillagePlanning;
import org.junit.jupiter.api.Test;

class VerrouillageConstraintsTest extends ConstraintTestBase {

    private final Stand standStrat = standWithStrategy("STAND-STRAT");
    private final Creneau creneauMatin = matin("J1-MATIN", 1, D1);
    private final Creneau creneauAprem = afternoon("J1-AM", 1, D1);

    private static VerrouillagePlanning verrouAnimateur(String id, Animateur animateur) {
        VerrouillagePlanning verrouillage = new VerrouillagePlanning(id, TypeVerrouillage.ANIMATEUR);
        verrouillage.setAnimateurId(animateur.getId());
        return verrouillage;
    }

    private PosteAffectation posteVerrouille(Stand stand, Creneau creneau, Animateur animateur) {
        PosteAffectation poste = poste(stand, creneau, animateur);
        poste.setVerrouille(true);
        return poste;
    }

    @Test
    void nouveauPosteDonneAUnAnimateurVerrouilleEstPenalise() {
        Animateur a1 = referentMajeur("A1");
        verify("animateurVerrouilleFige")
                .given(
                        a1,
                        posteVerrouille(standStrat, creneauMatin, a1),
                        poste(standStrat, creneauAprem, a1),
                        verrouAnimateur("V1", a1))
                .penalizesBy(1);
    }

    @Test
    void postesFigesDUnAnimateurVerrouilleNeSontPasPenalises() {
        Animateur a1 = referentMajeur("A1");
        verify("animateurVerrouilleFige")
                .given(
                        a1,
                        posteVerrouille(standStrat, creneauMatin, a1),
                        posteVerrouille(standStrat, creneauAprem, a1),
                        verrouAnimateur("V1", a1))
                .penalizesBy(0);
    }

    @Test
    void unAutreAnimateurResteLibreDEtreAffecte() {
        Animateur a1 = referentMajeur("A1");
        Animateur a2 = majeurAutonome("A2");
        verify("animateurVerrouilleFige")
                .given(
                        a1,
                        a2,
                        posteVerrouille(standStrat, creneauMatin, a1),
                        poste(standStrat, creneauAprem, a2),
                        verrouAnimateur("V1", a1))
                .penalizesBy(0);
    }

    private static VerrouillagePlanning verrouAnimateurCreneau(String id, Animateur animateur, Creneau creneau) {
        VerrouillagePlanning verrouillage = new VerrouillagePlanning(id, TypeVerrouillage.ANIMATEUR_CRENEAU);
        verrouillage.setAnimateurId(animateur.getId());
        verrouillage.setCreneauId(creneau.getId());
        return verrouillage;
    }

    @Test
    void nouveauPosteSurLeCreneauVerrouilleEstPenalise() {
        Animateur a1 = referentMajeur("A1");
        verify("animateurVerrouilleCreneauFige")
                .given(a1, poste(standStrat, creneauMatin, a1), verrouAnimateurCreneau("V1", a1, creneauMatin))
                .penalizesBy(1);
    }

    @Test
    void posteFigeParLEchangeValideNEstPasPenalise() {
        Animateur a1 = referentMajeur("A1");
        verify("animateurVerrouilleCreneauFige")
                .given(
                        a1,
                        posteVerrouille(standStrat, creneauMatin, a1),
                        verrouAnimateurCreneau("V1", a1, creneauMatin))
                .penalizesBy(0);
    }

    @Test
    void lesAutresCreneauxDeLAnimateurRestentLibres() {
        Animateur a1 = referentMajeur("A1");
        verify("animateurVerrouilleCreneauFige")
                .given(
                        a1,
                        posteVerrouille(standStrat, creneauMatin, a1),
                        poste(standStrat, creneauAprem, a1),
                        verrouAnimateurCreneau("V1", a1, creneauMatin))
                .penalizesBy(0);
    }

    /** The (animateur, timeslot) lock restrains that animateur only. */
    @Test
    void unAutreAnimateurResteLibreSurLeCreneauVerrouille() {
        Animateur a1 = referentMajeur("A1");
        Animateur a2 = majeurAutonome("A2");
        verify("animateurVerrouilleCreneauFige")
                .given(
                        a1,
                        a2,
                        posteVerrouille(standStrat, creneauMatin, a1),
                        poste(standStrat, creneauMatin, a2),
                        verrouAnimateurCreneau("V1", a1, creneauMatin))
                .penalizesBy(0);
    }

    @Test
    void unVerrouillageJourNeContraintPersonneParLuiMeme() {
        Animateur a1 = referentMajeur("A1");
        VerrouillagePlanning verrouillage = new VerrouillagePlanning("V1", TypeVerrouillage.JOUR);
        verrouillage.setJour(D1);
        verify("animateurVerrouilleFige")
                .given(a1, poste(standStrat, creneauMatin, a1), verrouillage)
                .penalizesBy(0);
    }

    /* ------------------- counted, never reproached (ADR 0044) ------------------- */

    @Test
    void aPastSeatOfALockedAnimateurIsNeverAViolationEvenUnpinned() {
        // The analyses of the persisted plan mark the past without pinning
        // it: the rule has to read the flag, not only the pin.
        Animateur a1 = referentMajeur("A1");
        verify("animateurVerrouilleFige")
                .given(a1, postePasse(standStrat, creneauMatin, a1), verrouAnimateur("V1", a1))
                .penalizesBy(0);
    }
}
