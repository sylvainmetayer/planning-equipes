package dev.sylvain.planning.solver.constraints;

import java.time.LocalTime;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Emplacement;
import dev.sylvain.planning.domain.NiveauCompetence;
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
        // A1 porte quatre postes contre un seul pour A2 : déséquilibre marqué.
        verify("equilibrerCharge")
                .given(poste(standStrat, creneauMatin, a1),
                        poste(standStrat, creneauAprem, a1),
                        poste(standStrat, matin("J2-MATIN", 2, D2), a1),
                        poste(standStrat, apresMidi("J2-AM", 2, D2), a1),
                        poste(standStrategie("STAND-2"), creneauMatin, majeurAutonome("A2")))
                .penalizesByMoreThan(0);
    }

    @Test
    void petitDesequilibreEstDesormaisPenalise() {
        // A1 porte deux postes contre un seul pour A2 : avant la mise à l'échelle
        // de l'unfairness (UNFAIRNESS_SCALE dans QualiteConstraints), ce faible
        // écart tronquait exactement à 0 via intValue() et le solveur n'avait
        // aucun gradient pour corriger un déséquilibre modéré. C'est exactement le
        // piège que la mise à l'échelle corrige.
        Animateur a1 = majeurReferent("A1");
        verify("equilibrerCharge")
                .given(poste(standStrat, creneauMatin, a1),
                        poste(standStrat, creneauAprem, a1),
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

    @Test
    void enchainerDeuxStandsEpuisantsSansReposEstPenalise() {
        Stand standEpuisant1 = standEpuisant("STAND-EPUISANT-1");
        Stand standEpuisant2 = standEpuisant("STAND-EPUISANT-2");
        Creneau matin = creneau("J1-MATIN5", 1, D1, LocalTime.of(9, 0), LocalTime.of(13, 0));
        Creneau suite = creneau("J1-SUITE5", 1, D1, LocalTime.of(13, 0), LocalTime.of(17, 0));
        Animateur a1 = majeurReferent("A1");
        verify("eviterEnchainementStandsEpuisants")
                .given(poste(standEpuisant1, matin, a1),
                        poste(standEpuisant2, suite, a1))
                .penalizesBy(1);
    }

    @Test
    void enchainerStandEpuisantPuisStandNormalNEstPasPenalise() {
        Stand standEpuisant = standEpuisant("STAND-EPUISANT-3");
        Creneau matin = creneau("J1-MATIN6", 1, D1, LocalTime.of(9, 0), LocalTime.of(13, 0));
        Creneau suite = creneau("J1-SUITE6", 1, D1, LocalTime.of(13, 0), LocalTime.of(17, 0));
        Animateur a1 = majeurReferent("A1");
        verify("eviterEnchainementStandsEpuisants")
                .given(poste(standEpuisant, matin, a1),
                        poste(standStrat, suite, a1))
                .penalizesBy(0);
    }

    @Test
    void deuxStandsEpuisantsSansCreneauxConsecutifsNEstPasPenalise() {
        Stand standEpuisant1 = standEpuisant("STAND-EPUISANT-4");
        Stand standEpuisant2 = standEpuisant("STAND-EPUISANT-5");
        Animateur a1 = majeurReferent("A1");
        verify("eviterEnchainementStandsEpuisants")
                .given(poste(standEpuisant1, creneauMatin, a1),
                        poste(standEpuisant2, creneauAprem, a1))
                .penalizesBy(0);
    }

    @Test
    void appreciationAbsenteEstPenalisee() {
        Animateur sansAppreciationStrategie = animateur("A1", D1.minusYears(30),
                Map.of("AMBIANCE", NiveauCompetence.AUTONOME));
        verify("appreciationIncompatible")
                .given(poste(standStrat, creneauMatin, sansAppreciationStrategie))
                .penalizesBy(1);
    }

    @Test
    void appreciationPresenteNEstPasPenalisee() {
        verify("appreciationIncompatible")
                .given(poste(standStrat, creneauMatin, majeurReferent("A1")))
                .penalizesBy(0);
    }

    @Test
    void souhaitAbsentEstPenalise() {
        Animateur sansSouhaitStrategie = animateurAvecSouhaits("A1", D1.minusYears(30),
                Map.of("STRATEGIE", NiveauCompetence.AUTONOME), "AMBIANCE");
        verify("souhaitsIncompatibles")
                .given(poste(standStrat, creneauMatin, sansSouhaitStrategie))
                .penalizesBy(1);
    }

    @Test
    void souhaitPresentNEstPasPenalise() {
        Animateur souhaiteStrategie = animateurAvecSouhaits("A1", D1.minusYears(30),
                Map.of("STRATEGIE", NiveauCompetence.AUTONOME), "STRATEGIE");
        verify("souhaitsIncompatibles")
                .given(poste(standStrat, creneauMatin, souhaiteStrategie))
                .penalizesBy(0);
    }

    @Test
    void deuxTypologiesDistinctesNeSontPasPenalisees() {
        Animateur a1 = animateur("A1", D1.minusYears(30), Map.of(
                "STRATEGIE", NiveauCompetence.AUTONOME,
                "AMBIANCE", NiveauCompetence.AUTONOME));
        verify("limiterTypologiesDistinctesParAnimateur")
                .given(poste(standStrategie("STAND-STRAT-2"), creneauMatin, a1),
                        poste(stand("STAND-AMBIANCE", false, "AMBIANCE"), creneauAprem, a1))
                .penalizesBy(0);
    }

    @Test
    void troisTypologiesDistinctesSontPenaliseesUnPoint() {
        Animateur a1 = animateur("A1", D1.minusYears(30), Map.of(
                "STRATEGIE", NiveauCompetence.AUTONOME,
                "AMBIANCE", NiveauCompetence.AUTONOME,
                "ENIGME", NiveauCompetence.AUTONOME));
        verify("limiterTypologiesDistinctesParAnimateur")
                .given(poste(standStrategie("STAND-STRAT-3"), creneauMatin, a1),
                        poste(stand("STAND-AMBIANCE-3", false, "AMBIANCE"), creneauAprem, a1),
                        poste(stand("STAND-ENIGME-3", false, "ENIGME"), matin("J2-MATIN3", 2, D2), a1))
                .penalizesBy(1);
    }

    @Test
    void cinqTypologiesDistinctesSontPenaliseesTroisPoints() {
        // Reprend l'exemple métier cité pour justifier la contrainte : un
        // animateur maîtrisant 5 typologies différentes est un mauvais cas.
        Animateur a1 = animateur("A1", D1.minusYears(30), Map.of(
                "STRATEGIE", NiveauCompetence.AUTONOME,
                "AMBIANCE", NiveauCompetence.AUTONOME,
                "ENIGME", NiveauCompetence.AUTONOME,
                "ADRESSE", NiveauCompetence.AUTONOME,
                "ROLE", NiveauCompetence.AUTONOME));
        verify("limiterTypologiesDistinctesParAnimateur")
                .given(poste(standStrategie("STAND-STRAT-5"), creneauMatin, a1),
                        poste(stand("STAND-AMBIANCE-5", false, "AMBIANCE"), creneauAprem, a1),
                        poste(stand("STAND-ENIGME-5", false, "ENIGME"), matin("J2-MATIN5", 2, D2), a1),
                        poste(stand("STAND-ADRESSE-5", false, "ADRESSE"), apresMidi("J2-AM5", 2, D2), a1),
                        poste(stand("STAND-ROLE-5", false, "ROLE"), matin("J3-MATIN5", 3, D3), a1))
                .penalizesBy(3);
    }
}
