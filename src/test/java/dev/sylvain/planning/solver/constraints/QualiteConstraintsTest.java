package dev.sylvain.planning.solver.constraints;

import java.time.LocalTime;

import org.junit.jupiter.api.Test;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Emplacement;
import dev.sylvain.planning.domain.Stand;

class QualiteConstraintsTest extends ConstraintTestBase {

    private final Stand standStrat = standStrategie("STAND-STRAT");
    private final Stand standPremium = standPremium("STAND-PREMIUM");
    private final Creneau creneauMatin = matin("J1-MATIN", 1, D1);
    private final Creneau creneauAprem = apresMidi("J1-AM", 1, D1);

    // Real coordinates in a fictional town centre: Place du Drapeau and la
    // Mairie are ~490 m apart (far), two nearby points on the same square are a
    // few dozen meters apart (close).
    private final Emplacement placeDrapeau = emplacement("PLACE-DRAPEAU", 46.6513, 2.2492);
    private final Emplacement mairie = emplacement("MAIRIE", 46.6490, 2.2547);
    private final Emplacement pointVoisin = emplacement("VOISIN", 46.6515, 2.2490);

    @Test
    void standSansReferentEstPenaliseEnMedium() {
        verify("standComplexeAvecReferent")
                .given(poste(standStrat, creneauMatin, majeurAutonome("A1")))
                .penalizesBy(1);
    }

    @Test
    void standAvecReferentNEstPasPenalise() {
        verify("standComplexeAvecReferent")
                .given(poste(standStrat, creneauMatin, majeurReferent("A1")))
                .penalizesBy(0);
    }

    @Test
    void chargeEquilibreeNEstPasPenalisee() {
        // Deux animateurs avec un poste chacun : répartition parfaite.
        verify("equilibrerCharge")
                .given(poste(standStrat, creneauMatin, majeurReferent("A1")),
                        poste(standStrat, creneauAprem, majeurAutonome("A2")))
                .penalizesBy(0);
    }

    @Test
    void chargeDesequilibreeEstPenalisee() {
        Animateur a1 = majeurReferent("A1");
        // A1 porte quatre postes contre un seul pour A2. NB : la contrainte tronque
        // l'« unfairness » via intValue(), donc un écart faible (2 contre 1) donne 0 ;
        // il faut un déséquilibre marqué pour qu'une pénalité entière apparaisse.
        verify("equilibrerCharge")
                .given(poste(standStrat, creneauMatin, a1),
                        poste(standStrat, creneauAprem, a1),
                        poste(standStrat, matin("J2-MATIN", 2, D2), a1),
                        poste(standStrat, apresMidi("J2-AM", 2, D2), a1),
                        poste(standStrategie("STAND-2"), creneauMatin, majeurAutonome("A2")))
                .penalizesByMoreThan(0);
    }

    @Test
    void majoriteDeMineursSurUnCreneauEstPenalisee() {
        // Deux mineurs, aucun majeur : pénalité = mineurs - majeurs = 2.
        verify("repartitionMineursParCreneau")
                .given(poste(standStrat, creneauMatin, mineurDebutant("M1")),
                        poste(standStrat, creneauMatin, mineurDebutant("M2")))
                .penalizesBy(2);
    }

    @Test
    void autantDeMineursQueDeMajeursNEstPasPenalise() {
        verify("repartitionMineursParCreneau")
                .given(poste(standStrat, creneauMatin, mineurDebutant("M1")),
                        poste(standStrat, creneauMatin, majeurReferent("A1")))
                .penalizesBy(0);
    }

    @Test
    void debutantSurStandPremiumEstPenalise() {
        verify("experienceRequisePourStandsPremium")
                .given(poste(standPremium, creneauMatin, mineurDebutant("M1")))
                .penalizesBy(1);
    }

    @Test
    void referentSurStandPremiumNEstPasPenalise() {
        verify("experienceRequisePourStandsPremium")
                .given(poste(standPremium, creneauMatin, majeurReferent("A1")))
                .penalizesBy(0);
    }

    @Test
    void deuxAnimateursDifferentsSurStandPremiumADesCreneauxDifferentsEstPenalise() {
        verify("eviterRoulementStandsPremium")
                .given(poste(standPremium, creneauMatin, majeurReferent("A1")),
                        poste(standPremium, creneauAprem, majeurReferent("A2")))
                .penalizesBy(1);
    }

    @Test
    void memeAnimateurSurStandPremiumADesCreneauxDifferentsNEstPasPenalise() {
        Animateur a1 = majeurReferent("A1");
        verify("eviterRoulementStandsPremium")
                .given(poste(standPremium, creneauMatin, a1),
                        poste(standPremium, creneauAprem, a1))
                .penalizesBy(0);
    }

    @Test
    void deuxAnimateursDifferentsSurStandPremiumAuMemeCreneauNEstPasPenalise() {
        // Simultaneous multi-staffing on the same slot is not a rotation.
        verify("eviterRoulementStandsPremium")
                .given(poste(standPremium, creneauMatin, majeurReferent("A1")),
                        poste(standPremium, creneauMatin, majeurReferent("A2")))
                .penalizesBy(0);
    }

    @Test
    void changerDeStandEloigneEntreDeuxCreneauxConsecutifsEstPenalise() {
        Stand standDrapeau = standAvecEmplacement("STAND-DRAPEAU", placeDrapeau);
        Stand standMairie = standAvecEmplacement("STAND-MAIRIE", mairie);
        Creneau matin = creneau("J1-MATIN2", 1, D1, LocalTime.of(9, 0), LocalTime.of(13, 0));
        Creneau suite = creneau("J1-SUITE", 1, D1, LocalTime.of(13, 0), LocalTime.of(17, 0));
        Animateur a1 = majeurReferent("A1");
        verify("eviterChangementEmplacementEloigne")
                .given(poste(standDrapeau, matin, a1),
                        poste(standMairie, suite, a1))
                .penalizesBy(1);
    }

    @Test
    void changerDeStandProcheEntreDeuxCreneauxConsecutifsNEstPasPenalise() {
        Stand standDrapeau = standAvecEmplacement("STAND-DRAPEAU", placeDrapeau);
        Stand standVoisin = standAvecEmplacement("STAND-VOISIN", pointVoisin);
        Creneau matin = creneau("J1-MATIN3", 1, D1, LocalTime.of(9, 0), LocalTime.of(13, 0));
        Creneau suite = creneau("J1-SUITE3", 1, D1, LocalTime.of(13, 0), LocalTime.of(17, 0));
        Animateur a1 = majeurReferent("A1");
        verify("eviterChangementEmplacementEloigne")
                .given(poste(standDrapeau, matin, a1),
                        poste(standVoisin, suite, a1))
                .penalizesBy(0);
    }

    @Test
    void changerDeStandEloigneSansCreneauxConsecutifsNEstPasPenalise() {
        // creneauMatin (9h-13h) and creneauAprem (14h-18h) are not back-to-back.
        Stand standDrapeau = standAvecEmplacement("STAND-DRAPEAU", placeDrapeau);
        Stand standMairie = standAvecEmplacement("STAND-MAIRIE", mairie);
        Animateur a1 = majeurReferent("A1");
        verify("eviterChangementEmplacementEloigne")
                .given(poste(standDrapeau, creneauMatin, a1),
                        poste(standMairie, creneauAprem, a1))
                .penalizesBy(0);
    }

    @Test
    void changerDeStandSansEmplacementNEstPasPenalise() {
        Creneau matin = creneau("J1-MATIN4", 1, D1, LocalTime.of(9, 0), LocalTime.of(13, 0));
        Creneau suite = creneau("J1-SUITE4", 1, D1, LocalTime.of(13, 0), LocalTime.of(17, 0));
        Animateur a1 = majeurReferent("A1");
        verify("eviterChangementEmplacementEloigne")
                .given(poste(standStrategie("STAND-A"), matin, a1),
                        poste(standStrategie("STAND-B"), suite, a1))
                .penalizesBy(0);
    }
}
