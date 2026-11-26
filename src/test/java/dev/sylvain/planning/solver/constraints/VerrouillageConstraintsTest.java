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

    private PosteAffectation posteVerrouille(Stand stand, Creneau creneau, Animateur animateur) {
        PosteAffectation poste = poste(stand, creneau, animateur);
        poste.setVerrouille(true);
        return poste;
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

    /** A day lock and a stand-day lock only pin seats: no rule reads them. */
    @Test
    void unVerrouillageJourOuStandJourNeContraintPersonneParLuiMeme() {
        Animateur a1 = referentMajeur("A1");
        VerrouillagePlanning jour = new VerrouillagePlanning("V1", TypeVerrouillage.JOUR);
        jour.setJour(D1);
        VerrouillagePlanning standJour = new VerrouillagePlanning("V2", TypeVerrouillage.STAND_JOUR);
        standJour.setStandId(standStrat.getId());
        standJour.setJour(D1);
        verify("animateurVerrouilleCreneauFige")
                .given(a1, poste(standStrat, creneauMatin, a1), jour, standJour)
                .penalizesBy(0);
    }

    /* ------------------- counted, never reproached (ADR 0044) ------------------- */

    @Test
    void aPastSeatOfALockedAnimateurOnATimeslotIsNeverAViolationEvenUnpinned() {
        // The analyses of the persisted plan mark the past without pinning
        // it: the rule has to read the flag, not only the pin.
        Animateur a1 = referentMajeur("A1");
        verify("animateurVerrouilleCreneauFige")
                .given(a1, postePasse(standStrat, creneauMatin, a1), verrouAnimateurCreneau("V1", a1, creneauMatin))
                .penalizesBy(0);
    }
}
