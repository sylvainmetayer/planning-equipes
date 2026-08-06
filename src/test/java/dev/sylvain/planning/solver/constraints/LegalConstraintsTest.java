package dev.sylvain.planning.solver.constraints;

import org.junit.jupiter.api.Test;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypologieJeu;

class LegalConstraintsTest extends ConstraintTestBase {

    private final Stand standStrat = standStrategie("STAND-STRAT");
    private final Stand standMajeurs = stand("STAND-MAJ", true, TypologieJeu.STRATEGIE);
    private final Creneau creneauMatin = matin("J1-MATIN", 1, D1);
    private final Creneau creneauNuit = nuit("J1-NUIT", 1, D1);
    private final Creneau matinJ2 = matin("J2-MATIN", 2, D2);
    private final Creneau apremJ2 = apresMidi("J2-AM", 2, D2);

    @Test
    void mineurSurStandReserveAuxMajeursEstPenalise() {
        verify("standReserveAuxMajeurs")
                .given(poste(standMajeurs, creneauMatin, mineurDebutant("M1")))
                .penalizesBy(1);
    }

    @Test
    void majeurSurStandReserveAuxMajeursNEstPasPenalise() {
        verify("standReserveAuxMajeurs")
                .given(poste(standMajeurs, creneauMatin, majeurReferent("A1")))
                .penalizesBy(0);
    }

    @Test
    void mineurSansMajeurSurLeStandEstPenalise() {
        verify("mineurNecessiteEncadrementMajeur")
                .given(poste(standStrat, creneauMatin, mineurDebutant("M1")))
                .penalizesBy(1);
    }

    @Test
    void mineurEncadreParUnMajeurNEstPasPenalise() {
        verify("mineurNecessiteEncadrementMajeur")
                .given(poste(standStrat, creneauMatin, mineurDebutant("M1")),
                        poste(standStrat, creneauMatin, majeurReferent("A1")))
                .penalizesBy(0);
    }

    @Test
    void mineurTravaillantLaNuitEstPenalise() {
        verify("travailDeNuitInterditPourMineur")
                .given(poste(standStrat, creneauNuit, mineurDebutant("M1")))
                .penalizesBy(1);
    }

    @Test
    void majeurTravaillantLaNuitNEstPasPenalise() {
        verify("travailDeNuitInterditPourMineur")
                .given(poste(standStrat, creneauNuit, majeurReferent("A1")))
                .penalizesBy(0);
    }

    @Test
    void mineurDepassantHuitHeuresParJourEstPenalise() {
        // Un créneau de 9h (540 min) dépasse de 60 min le plafond de 8h (480 min).
        Creneau journee = journeeLongue("J1-LONG", 1, D1);
        verify("dureeQuotidienneMaxMineur")
                .given(poste(standStrat, journee, mineurDebutant("M1")))
                .penalizesBy(60);
    }

    @Test
    void mineurSousHuitHeuresParJourNEstPasPenalise() {
        verify("dureeQuotidienneMaxMineur")
                .given(poste(standStrat, creneauMatin, mineurDebutant("M1")))
                .penalizesBy(0);
    }

    @Test
    void majeurNEstPasConcerneParLePlafondQuotidien() {
        Creneau journee = journeeLongue("J1-LONG", 1, D1);
        verify("dureeQuotidienneMaxMineur")
                .given(poste(standStrat, journee, majeurReferent("A1")))
                .penalizesBy(0);
    }

    @Test
    void mineurAvecReposInsuffisantApresUnCreneauDeNuitEstPenalise() {
        Animateur mineur = mineurDebutant("M1");
        verify("reposQuotidienMineur")
                .given(poste(standStrat, creneauNuit, mineur),
                        poste(standStrat, matinJ2, mineur))
                .penalizesBy(1);
    }

    @Test
    void mineurReprenantLApresMidiApresUneNuitNEstPasPenalise() {
        Animateur mineur = mineurDebutant("M1");
        verify("reposQuotidienMineur")
                .given(poste(standStrat, creneauNuit, mineur),
                        poste(standStrat, apremJ2, mineur))
                .penalizesBy(0);
    }

    @Test
    void majeurNEstPasConcerneParLeReposQuotidien() {
        Animateur majeur = majeurReferent("A1");
        verify("reposQuotidienMineur")
                .given(poste(standStrat, creneauNuit, majeur),
                        poste(standStrat, matinJ2, majeur))
                .penalizesBy(0);
    }

    @Test
    void depassementDureeHebdomadaireMaxEstPenalise() {
        // Same ISO week as D1/D2 (2026-07-08/09): two 4h slots (matin + aprem)
        // total 480 min, 80 min over a 400 min cap.
        Animateur majeur = majeurReferent("A1");
        ParametresLegaux parametres = new ParametresLegaux(400);
        verify("dureeHebdomadaireMax")
                .given(poste(standStrat, creneauMatin, majeur),
                        poste(standStrat, apremJ2, majeur),
                        parametres)
                .penalizesBy(80);
    }

    @Test
    void sousLaDureeHebdomadaireMaxNEstPasPenalise() {
        Animateur majeur = majeurReferent("A1");
        ParametresLegaux parametres = new ParametresLegaux(500);
        verify("dureeHebdomadaireMax")
                .given(poste(standStrat, creneauMatin, majeur),
                        poste(standStrat, apremJ2, majeur),
                        parametres)
                .penalizesBy(0);
    }

    // --- Art. L3162-1 : 35 h/semaine pour un mineur ------------------------

    @Test
    void mineurDepassant35HeuresParSemaineEstPenalise() {
        // Quatre journées de 9 h dans la même semaine ISO = 36 h, soit 60 min
        // au-dessus du plafond d'ordre public de 35 h (art. L3162-1).
        Animateur mineur = mineurDebutant("M1");
        verify("dureeHebdomadaireMaxMineur")
                .given(poste(standStrat, journeeLongue("J1-LONG", 1, D1), mineur),
                        poste(standStrat, journeeLongue("J2-LONG", 2, D2), mineur),
                        poste(standStrat, journeeLongue("J3-LONG", 3, D3), mineur),
                        poste(standStrat, journeeLongue("J4-LONG", 4, D4), mineur),
                        new ParametresLegaux())
                .penalizesBy(60);
    }

    @Test
    void mineurSousLes35HeuresParSemaineNEstPasPenalise() {
        Animateur mineur = mineurDebutant("M1");
        verify("dureeHebdomadaireMaxMineur")
                .given(poste(standStrat, journeeLongue("J1-LONG", 1, D1), mineur),
                        poste(standStrat, journeeLongue("J2-LONG", 2, D2), mineur),
                        poste(standStrat, journeeLongue("J3-LONG", 3, D3), mineur),
                        new ParametresLegaux())
                .penalizesBy(0);
    }

    @Test
    void majeurNEstPasConcerneParLePlafondHebdomadaireMineur() {
        Animateur majeur = majeurReferent("A1");
        verify("dureeHebdomadaireMaxMineur")
                .given(poste(standStrat, journeeLongue("J1-LONG", 1, D1), majeur),
                        poste(standStrat, journeeLongue("J2-LONG", 2, D2), majeur),
                        poste(standStrat, journeeLongue("J3-LONG", 3, D3), majeur),
                        poste(standStrat, journeeLongue("J4-LONG", 4, D4), majeur),
                        new ParametresLegaux())
                .penalizesBy(0);
    }

    @Test
    void mineurNEstPlusSoumisAuPlafondHebdomadaireMajeur() {
        // Régression de la violation B3 de l'audit : avant le correctif, un
        // mineur était plafonné à 48 h par dureeHebdomadaireMax.
        Animateur mineur = mineurDebutant("M1");
        ParametresLegaux parametres = new ParametresLegaux(400);
        verify("dureeHebdomadaireMax")
                .given(poste(standStrat, creneauMatin, mineur),
                        poste(standStrat, apremJ2, mineur),
                        parametres)
                .penalizesBy(0);
    }

    @Test
    void deuxAnimateursDistinctsNeSontPasCumulesEnsemble() {
        Animateur a1 = majeurReferent("A1");
        Animateur a2 = majeurReferent("A2");
        ParametresLegaux parametres = new ParametresLegaux(400);
        verify("dureeHebdomadaireMax")
                .given(poste(standStrat, creneauMatin, a1),
                        poste(standStrat, apremJ2, a2),
                        parametres)
                .penalizesBy(0);
    }
}
