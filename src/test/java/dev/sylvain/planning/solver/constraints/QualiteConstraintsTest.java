package dev.sylvain.planning.solver.constraints;

import dev.sylvain.planning.domain.AffectationPubliee;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Emplacement;
import dev.sylvain.planning.domain.NiveauCompetence;
import dev.sylvain.planning.domain.OuvertureStand;
import dev.sylvain.planning.domain.ParametresQualite;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class QualiteConstraintsTest extends ConstraintTestBase {

    private final Stand standStrat = standWithStrategy("STAND-STRAT");
    private final Stand standPremium = standPremium("STAND-PREMIUM");
    private final Creneau creneauMatin = matin("J1-MATIN", 1, D1);
    private final Creneau creneauAprem = afternoon("J1-AM", 1, D1);

    // Coordinates of the sample locations seeded by V9: Place du Drapeau and la
    // Mairie are ~490 m apart (far), two nearby points on the same square are a
    // few dozen meters apart (close).
    private final Emplacement placeDrapeau = emplacement("PLACE-DRAPEAU", 46.6513, 2.2492);
    private final Emplacement mairie = emplacement("MAIRIE", 46.6490, 2.2547);
    private final Emplacement pointVoisin = emplacement("VOISIN", 46.6515, 2.2490);

    /* ------------------------- stabiliteDuPlanPublie ------------------------- */

    private AffectationPubliee publie(Stand stand, Creneau creneau, String animateurId) {
        return new AffectationPubliee(stand.getId(), creneau.getId(), animateurId);
    }

    @Test
    void stabilityIsSilentWhileNothingWasPublished() {
        verify("stabiliteDuPlanPublie")
                .given(poste(standStrat, creneauMatin, majeurAutonome("A1")))
                .penalizesBy(0);
    }

    @Test
    void keepingThePublishedHolderCostsNothing() {
        verify("stabiliteDuPlanPublie")
                .given(poste(standStrat, creneauMatin, majeurAutonome("A1")), publie(standStrat, creneauMatin, "A1"))
                .penalizesBy(0);
    }

    @Test
    void replacingThePublishedHolderCostsOneMediumPerPersonMoved() {
        // A1 was told about the morning on the stand; A2 now holds it.
        verify("stabiliteDuPlanPublie")
                .given(poste(standStrat, creneauMatin, majeurAutonome("A2")), publie(standStrat, creneauMatin, "A1"))
                .penalizesBy(1);
    }

    @Test
    void swappingTwoPublishedHoldersCostsTwo() {
        verify("stabiliteDuPlanPublie")
                .given(
                        poste(standStrat, creneauMatin, majeurAutonome("A2")),
                        poste(standPremium, creneauMatin, majeurAutonome("A1")),
                        publie(standStrat, creneauMatin, "A1"),
                        publie(standPremium, creneauMatin, "A2"))
                .penalizesBy(2);
    }

    @Test
    void aSeatThePublicationNeverHadIsFree() {
        // A stand created after the publication: nobody was told anything about it.
        verify("stabiliteDuPlanPublie")
                .given(poste(standPremium, creneauMatin, majeurAutonome("A1")), publie(standStrat, creneauMatin, "A1"))
                .penalizesBy(0);
    }

    @Test
    void anExtraSeatOnAPublishedLineCountsItsNewcomer() {
        // The stand grew from one seat to two on that créneau: A1 stays, A2 is new there.
        verify("stabiliteDuPlanPublie")
                .given(
                        poste(standStrat, creneauMatin, majeurAutonome("A1")),
                        poste(standStrat, creneauMatin, majeurAutonome("A2")),
                        publie(standStrat, creneauMatin, "A1"))
                .penalizesBy(1);
    }

    @Test
    void aPublishedSeatLeftEmptyCostsOneLikeAReplacement() {
        // The holder was told they worked there: the hole is theirs to be told
        // about, and counting it keeps the solver from emptying published seats
        // for free (ADR 0025).
        verify("stabiliteDuPlanPublie")
                .given(poste(standStrat, creneauMatin, null), publie(standStrat, creneauMatin, "A1"))
                .penalizesBy(1);
    }

    @Test
    void anEmptySeatOnALineNeverPublishedIsFree() {
        verify("stabiliteDuPlanPublie")
                .given(poste(standStrat, creneauMatin, null), publie(standStrat, creneauAprem, "A1"))
                .penalizesBy(0);
    }

    @Test
    void standSansReferentEstPenaliseEnMedium() {
        verify("standComplexeAvecReferent")
                .given(poste(standStrat, creneauMatin, majeurAutonome("A1")))
                .penalizesBy(1);
    }

    @Test
    void standAvecReferentNEstPasPenalise() {
        verify("standComplexeAvecReferent")
                .given(poste(standStrat, creneauMatin, referentMajeur("A1")))
                .penalizesBy(0);
    }

    @Test
    void chargeEquilibreeNEstPasPenalisee() {
        // Two animateurs with one seat each: a perfect spread.
        verify("equilibrerCharge")
                .given(
                        poste(standStrat, creneauMatin, referentMajeur("A1")),
                        poste(standStrat, creneauAprem, majeurAutonome("A2")))
                .penalizesBy(0);
    }

    @Test
    void chargeDesequilibreeEstPenalisee() {
        Animateur a1 = referentMajeur("A1");
        // A1 carries four seats against a single one for A2: a marked imbalance.
        verify("equilibrerCharge")
                .given(
                        poste(standStrat, creneauMatin, a1),
                        poste(standStrat, creneauAprem, a1),
                        poste(standStrat, matin("J2-MATIN", 2, D2), a1),
                        poste(standStrat, afternoon("J2-AM", 2, D2), a1),
                        poste(standWithStrategy("STAND-2"), creneauMatin, majeurAutonome("A2")))
                .penalizesByMoreThan(0);
    }

    @Test
    void petitDesequilibreEstDesormaisPenalise() {
        // A1 carries two seats against a single one for A2: before the unfairness
        // was scaled up (UNFAIRNESS_SCALE in QualiteConstraints), that small gap
        // truncated to exactly 0 through intValue() and the solver had no gradient
        // to fix a moderate imbalance. That is precisely the score trap the
        // scaling removes.
        Animateur a1 = referentMajeur("A1");
        verify("equilibrerCharge")
                .given(
                        poste(standStrat, creneauMatin, a1),
                        poste(standStrat, creneauAprem, a1),
                        poste(standWithStrategy("STAND-2"), creneauMatin, majeurAutonome("A2")))
                .penalizesByMoreThan(0);
    }

    @Test
    void majoriteDeMineursSurUnCreneauEstPenalisee() {
        // Two mineurs, no majeur: penalty = mineurs - majeurs = 2.
        verify("repartitionMineursParCreneau")
                .given(
                        poste(standStrat, creneauMatin, mineurDebutant("M1")),
                        poste(standStrat, creneauMatin, mineurDebutant("M2")))
                .penalizesBy(2);
    }

    @Test
    void autantDeMineursQueDeMajeursNEstPasPenalise() {
        verify("repartitionMineursParCreneau")
                .given(
                        poste(standStrat, creneauMatin, mineurDebutant("M1")),
                        poste(standStrat, creneauMatin, referentMajeur("A1")))
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
                .given(poste(standPremium, creneauMatin, referentMajeur("A1")))
                .penalizesBy(0);
    }

    @Test
    void deuxAnimateursDifferentsSurStandPremiumADesCreneauxDifferentsEstPenalise() {
        verify("eviterRoulementStandsPremium")
                .given(
                        poste(standPremium, creneauMatin, referentMajeur("A1")),
                        poste(standPremium, creneauAprem, referentMajeur("A2")))
                .penalizesBy(1);
    }

    @Test
    void memeAnimateurSurStandPremiumADesCreneauxDifferentsNEstPasPenalise() {
        Animateur a1 = referentMajeur("A1");
        verify("eviterRoulementStandsPremium")
                .given(poste(standPremium, creneauMatin, a1), poste(standPremium, creneauAprem, a1))
                .penalizesBy(0);
    }

    @Test
    void chaqueAnimateurSupplementaireSurUnStandPremiumCouteUnPointDePlus() {
        // Four heads on a one-seat stand: three more than the crew it needs.
        // The former pair-counting formulation scored 6 for the same planning,
        // and grew quadratically from there.
        verify("eviterRoulementStandsPremium")
                .given(
                        poste(standPremium, creneauMatin, referentMajeur("A1")),
                        poste(standPremium, creneauAprem, referentMajeur("A2")),
                        poste(
                                standPremium,
                                creneau("J2-MATIN", 2, D2, LocalTime.of(9, 0), LocalTime.of(13, 0)),
                                referentMajeur("A3")),
                        poste(
                                standPremium,
                                creneau("J2-APREM", 2, D2, LocalTime.of(14, 0), LocalTime.of(18, 0)),
                                referentMajeur("A4")))
                .penalizesBy(3);
    }

    @Test
    void unEquipageCompletTenuParLesMemesPersonnesNEstPasPenalise() {
        // Two seats at a time held by the same two people all along: perfect
        // continuity, not a rotation — whatever the number of créneaux.
        Stand standDeuxPlaces = standPremium("STAND-PREMIUM-2");
        standDeuxPlaces.setEffectifMin(2);
        Animateur a1 = referentMajeur("A1");
        Animateur a2 = referentMajeur("A2");
        verify("eviterRoulementStandsPremium")
                .given(
                        poste(standDeuxPlaces, creneauMatin, a1),
                        poste(standDeuxPlaces, creneauMatin, a2),
                        poste(standDeuxPlaces, creneauAprem, a1),
                        poste(standDeuxPlaces, creneauAprem, a2))
                .penalizesBy(0);
    }

    @Test
    void deuxAnimateursDifferentsSurStandPremiumAuMemeCreneauNEstPasPenalise() {
        // Simultaneous multi-staffing on the same slot is not a rotation: the
        // stand needs both of them at once.
        Stand standDeuxPlaces = standPremium("STAND-PREMIUM-SIMULTANE");
        standDeuxPlaces.setEffectifMin(2);
        verify("eviterRoulementStandsPremium")
                .given(
                        poste(standDeuxPlaces, creneauMatin, referentMajeur("A1")),
                        poste(standDeuxPlaces, creneauMatin, referentMajeur("A2")))
                .penalizesBy(0);
    }

    @Test
    void lEquipageDUnStandPremiumSuitLEffectifDeSaFenetre() {
        // Minimum 1 on the stand, but its morning window asks for three: three
        // faces on that slot are the crew, not a rotation. A fourth person on the
        // afternoon slot, whose window asks for one, is one head beyond the
        // largest crew the stand ever needs.
        Stand stand = standPremium("STAND-PREMIUM-FENETRE");
        stand.setOuvertures(List.of(
                new OuvertureStand(null, D1, creneauMatin.getHeureDebut(), creneauMatin.getHeureFin(), null, 3),
                new OuvertureStand(null, D1, creneauAprem.getHeureDebut(), creneauAprem.getHeureFin(), null, 1)));
        Animateur a1 = referentMajeur("A1");
        Animateur a2 = referentMajeur("A2");
        Animateur a3 = referentMajeur("A3");
        verify("eviterRoulementStandsPremium")
                .given(poste(stand, creneauMatin, a1), poste(stand, creneauMatin, a2), poste(stand, creneauMatin, a3))
                .penalizesBy(0);
        verify("eviterRoulementStandsPremium")
                .given(
                        poste(stand, creneauMatin, a1),
                        poste(stand, creneauMatin, a2),
                        poste(stand, creneauMatin, a3),
                        poste(stand, creneauAprem, referentMajeur("A4")))
                .penalizesBy(1);
    }

    @Test
    void unStandNonPremiumNEstJamaisPenalisePourSonRoulement() {
        verify("eviterRoulementStandsPremium")
                .given(
                        poste(standWithStrategy("STAND-ORDINAIRE"), creneauMatin, referentMajeur("A1")),
                        poste(standWithStrategy("STAND-ORDINAIRE"), creneauAprem, referentMajeur("A2")))
                .penalizesBy(0);
    }

    @Test
    void changerDeStandEloigneEntreDeuxCreneauxConsecutifsEstPenalise() {
        Stand standDrapeau = standWithEmplacement("STAND-DRAPEAU", placeDrapeau);
        Stand standMairie = standWithEmplacement("STAND-MAIRIE", mairie);
        Creneau matin = creneau("J1-MATIN2", 1, D1, LocalTime.of(9, 0), LocalTime.of(13, 0));
        Creneau suite = creneau("J1-SUITE", 1, D1, LocalTime.of(13, 0), LocalTime.of(17, 0));
        Animateur a1 = referentMajeur("A1");
        verify("eviterChangementEmplacementEloigne")
                .given(poste(standDrapeau, matin, a1), poste(standMairie, suite, a1))
                .penalizesBy(1);
    }

    @Test
    void changerDeStandProcheEntreDeuxCreneauxConsecutifsNEstPasPenalise() {
        Stand standDrapeau = standWithEmplacement("STAND-DRAPEAU", placeDrapeau);
        Stand standVoisin = standWithEmplacement("STAND-VOISIN", pointVoisin);
        Creneau matin = creneau("J1-MATIN3", 1, D1, LocalTime.of(9, 0), LocalTime.of(13, 0));
        Creneau suite = creneau("J1-SUITE3", 1, D1, LocalTime.of(13, 0), LocalTime.of(17, 0));
        Animateur a1 = referentMajeur("A1");
        verify("eviterChangementEmplacementEloigne")
                .given(poste(standDrapeau, matin, a1), poste(standVoisin, suite, a1))
                .penalizesBy(0);
    }

    @Test
    void changerDeStandEloigneSansCreneauxConsecutifsNEstPasPenalise() {
        // creneauMatin (9h-13h) and creneauAprem (14h-18h) are not back-to-back.
        Stand standDrapeau = standWithEmplacement("STAND-DRAPEAU", placeDrapeau);
        Stand standMairie = standWithEmplacement("STAND-MAIRIE", mairie);
        Animateur a1 = referentMajeur("A1");
        verify("eviterChangementEmplacementEloigne")
                .given(poste(standDrapeau, creneauMatin, a1), poste(standMairie, creneauAprem, a1))
                .penalizesBy(0);
    }

    @Test
    void changerDeStandSansEmplacementNEstPasPenalise() {
        Creneau matin = creneau("J1-MATIN4", 1, D1, LocalTime.of(9, 0), LocalTime.of(13, 0));
        Creneau suite = creneau("J1-SUITE4", 1, D1, LocalTime.of(13, 0), LocalTime.of(17, 0));
        Animateur a1 = referentMajeur("A1");
        verify("eviterChangementEmplacementEloigne")
                .given(poste(standWithStrategy("STAND-A"), matin, a1), poste(standWithStrategy("STAND-B"), suite, a1))
                .penalizesBy(0);
    }

    // --- limiterEmplacementsParJour (#82) ---------------------------------
    //
    // The cap travels as a ParametresQualite problem fact, so each test states
    // the plafond it exercises instead of depending on the configured default.

    private static final ParametresQualite PLAFOND_3 = new ParametresQualite(3);

    private final Emplacement halle = emplacement("HALLE", 46.6480, 2.2470);
    private final Emplacement chateau = emplacement("CHATEAU", 46.6535, 2.2440);

    /** Four postes on four emplacements, all on the same day, for one animateur. */
    private PosteAffectation[] dayOver(Animateur animateur, Emplacement... emplacements) {
        PosteAffectation[] postes = new PosteAffectation[emplacements.length];
        for (int i = 0; i < emplacements.length; i++) {
            Creneau creneau = creneau("J1-C" + i, 1, D1, LocalTime.of(8 + 2 * i, 0), LocalTime.of(9 + 2 * i, 0));
            postes[i] = poste(standWithEmplacement("STAND-" + i, emplacements[i]), creneau, animateur);
        }
        return postes;
    }

    @Test
    void journeeSousLePlafondDEmplacementsNEstPasPenalisee() {
        verify("limiterEmplacementsParJour")
                .given(concat(PLAFOND_3, dayOver(referentMajeur("A1"), placeDrapeau, mairie)))
                .penalizesBy(0);
    }

    @Test
    void journeeExactementAuPlafondDEmplacementsNEstPasPenalisee() {
        verify("limiterEmplacementsParJour")
                .given(concat(PLAFOND_3, dayOver(referentMajeur("A1"), placeDrapeau, mairie, halle)))
                .penalizesBy(0);
    }

    @Test
    void journeeAuDessusDuPlafondDEmplacementsEstPenalisee() {
        verify("limiterEmplacementsParJour")
                .given(concat(PLAFOND_3, dayOver(referentMajeur("A1"), placeDrapeau, mairie, halle, chateau)))
                .penalizesBy(1);
    }

    @Test
    void deuxPostesSurLeMemeEmplacementNeComptentQuUneZone() {
        // A → B → A: two zones, not two moves — the whole point of counting
        // distinct zones rather than transitions.
        verify("limiterEmplacementsParJour")
                .given(concat(PLAFOND_3, dayOver(referentMajeur("A1"), placeDrapeau, mairie, placeDrapeau, mairie)))
                .penalizesBy(0);
    }

    @Test
    void journeesDifferentesNeSAdditionnentPas() {
        Animateur a1 = referentMajeur("A1");
        verify("limiterEmplacementsParJour")
                .given(
                        PLAFOND_3,
                        poste(standWithEmplacement("S1", placeDrapeau), matin("J1-M", 1, D1), a1),
                        poste(standWithEmplacement("S2", mairie), afternoon("J1-A", 1, D1), a1),
                        poste(standWithEmplacement("S3", halle), matin("J2-M", 2, D2), a1),
                        poste(standWithEmplacement("S4", chateau), afternoon("J2-A", 2, D2), a1))
                .penalizesBy(0);
    }

    @Test
    void emplacementsNonRenseignesRendentLaRegleInerte() {
        Animateur a1 = referentMajeur("A1");
        verify("limiterEmplacementsParJour")
                .given(
                        PLAFOND_3,
                        poste(
                                standWithStrategy("S1"),
                                creneau("J1-A", 1, D1, LocalTime.of(8, 0), LocalTime.of(9, 0)),
                                a1),
                        poste(
                                standWithStrategy("S2"),
                                creneau("J1-B", 1, D1, LocalTime.of(9, 0), LocalTime.of(10, 0)),
                                a1),
                        poste(
                                standWithStrategy("S3"),
                                creneau("J1-C", 1, D1, LocalTime.of(10, 0), LocalTime.of(11, 0)),
                                a1),
                        poste(
                                standWithStrategy("S4"),
                                creneau("J1-D", 1, D1, LocalTime.of(11, 0), LocalTime.of(12, 0)),
                                a1))
                .penalizesBy(0);
    }

    @Test
    void unSeulDeplacementEloigneNEstPasPenaliseDeuxFois() {
        // The very fact eviterChangementEmplacementEloigne charges 1 for: under
        // the cap, this rule adds nothing, so the two never stack on it.
        Stand standDrapeau = standWithEmplacement("STAND-DRAPEAU", placeDrapeau);
        Stand standMairie = standWithEmplacement("STAND-MAIRIE", mairie);
        Creneau matin = creneau("J1-MATIN-DUP", 1, D1, LocalTime.of(9, 0), LocalTime.of(13, 0));
        Creneau suite = creneau("J1-SUITE-DUP", 1, D1, LocalTime.of(13, 0), LocalTime.of(17, 0));
        Animateur a1 = referentMajeur("A1");
        verify("eviterChangementEmplacementEloigne")
                .given(poste(standDrapeau, matin, a1), poste(standMairie, suite, a1))
                .penalizesBy(1);
        verify("limiterEmplacementsParJour")
                .given(PLAFOND_3, poste(standDrapeau, matin, a1), poste(standMairie, suite, a1))
                .penalizesBy(0);
    }

    /** {@code given(...)} is varargs of facts: prepends the plafond to a day's postes. */
    private static Object[] concat(ParametresQualite parametres, PosteAffectation[] postes) {
        Object[] facts = new Object[postes.length + 1];
        facts[0] = parametres;
        System.arraycopy(postes, 0, facts, 1, postes.length);
        return facts;
    }

    @Test
    void enchainerDeuxStandsEpuisantsSansReposEstPenalise() {
        Stand standEpuisant1 = standEpuisant("STAND-EPUISANT-1");
        Stand standEpuisant2 = standEpuisant("STAND-EPUISANT-2");
        Creneau matin = creneau("J1-MATIN5", 1, D1, LocalTime.of(9, 0), LocalTime.of(13, 0));
        Creneau suite = creneau("J1-SUITE5", 1, D1, LocalTime.of(13, 0), LocalTime.of(17, 0));
        Animateur a1 = referentMajeur("A1");
        verify("eviterEnchainementStandsEpuisants")
                .given(poste(standEpuisant1, matin, a1), poste(standEpuisant2, suite, a1))
                .penalizesBy(1);
    }

    @Test
    void enchainerStandEpuisantPuisStandNormalNEstPasPenalise() {
        Stand standEpuisant = standEpuisant("STAND-EPUISANT-3");
        Creneau matin = creneau("J1-MATIN6", 1, D1, LocalTime.of(9, 0), LocalTime.of(13, 0));
        Creneau suite = creneau("J1-SUITE6", 1, D1, LocalTime.of(13, 0), LocalTime.of(17, 0));
        Animateur a1 = referentMajeur("A1");
        verify("eviterEnchainementStandsEpuisants")
                .given(poste(standEpuisant, matin, a1), poste(standStrat, suite, a1))
                .penalizesBy(0);
    }

    @Test
    void deuxStandsEpuisantsSansCreneauxConsecutifsNEstPasPenalise() {
        Stand standEpuisant1 = standEpuisant("STAND-EPUISANT-4");
        Stand standEpuisant2 = standEpuisant("STAND-EPUISANT-5");
        Animateur a1 = referentMajeur("A1");
        verify("eviterEnchainementStandsEpuisants")
                .given(poste(standEpuisant1, creneauMatin, a1), poste(standEpuisant2, creneauAprem, a1))
                .penalizesBy(0);
    }

    @Test
    void appreciationAbsenteEstPenalisee() {
        Animateur sansAppreciationStrategie =
                animateur("A1", D1.minusYears(30), Map.of("AMBIANCE", NiveauCompetence.AUTONOME));
        verify("appreciationIncompatible")
                .given(poste(standStrat, creneauMatin, sansAppreciationStrategie))
                .penalizesBy(1);
    }

    @Test
    void appreciationPresenteNEstPasPenalisee() {
        verify("appreciationIncompatible")
                .given(poste(standStrat, creneauMatin, referentMajeur("A1")))
                .penalizesBy(0);
    }

    @Test
    void souhaitAbsentEstPenalise() {
        Animateur sansSouhaitStrategie = animateurWithSouhaits(
                "A1", D1.minusYears(30), Map.of("STRATEGIE", NiveauCompetence.AUTONOME), "AMBIANCE");
        verify("souhaitsIncompatibles")
                .given(poste(standStrat, creneauMatin, sansSouhaitStrategie))
                .penalizesBy(1);
    }

    @Test
    void souhaitPresentNEstPasPenalise() {
        Animateur souhaiteStrategie = animateurWithSouhaits(
                "A1", D1.minusYears(30), Map.of("STRATEGIE", NiveauCompetence.AUTONOME), "STRATEGIE");
        verify("souhaitsIncompatibles")
                .given(poste(standStrat, creneauMatin, souhaiteStrategie))
                .penalizesBy(0);
    }

    @Test
    void deuxTypologiesDistinctesNeSontPasPenalisees() {
        Animateur a1 = animateur(
                "A1",
                D1.minusYears(30),
                Map.of(
                        "STRATEGIE", NiveauCompetence.AUTONOME,
                        "AMBIANCE", NiveauCompetence.AUTONOME));
        verify("limiterTypologiesDistinctesParAnimateur")
                .given(
                        poste(standWithStrategy("STAND-STRAT-2"), creneauMatin, a1),
                        poste(stand("STAND-AMBIANCE", false, "AMBIANCE"), creneauAprem, a1))
                .penalizesBy(0);
    }

    @Test
    void troisTypologiesDistinctesSontPenaliseesUnPoint() {
        Animateur a1 = animateur(
                "A1",
                D1.minusYears(30),
                Map.of(
                        "STRATEGIE", NiveauCompetence.AUTONOME,
                        "AMBIANCE", NiveauCompetence.AUTONOME,
                        "ENIGME", NiveauCompetence.AUTONOME));
        verify("limiterTypologiesDistinctesParAnimateur")
                .given(
                        poste(standWithStrategy("STAND-STRAT-3"), creneauMatin, a1),
                        poste(stand("STAND-AMBIANCE-3", false, "AMBIANCE"), creneauAprem, a1),
                        poste(stand("STAND-ENIGME-3", false, "ENIGME"), matin("J2-MATIN3", 2, D2), a1),
                        new ParametresQualite())
                .penalizesBy(1);
    }

    @Test
    void cinqTypologiesDistinctesSontPenaliseesTroisPoints() {
        // Takes up the business example quoted to justify the constraint: an
        // animateur mastering 5 different typologies is a bad case.
        Animateur a1 = animateur(
                "A1",
                D1.minusYears(30),
                Map.of(
                        "STRATEGIE", NiveauCompetence.AUTONOME,
                        "AMBIANCE", NiveauCompetence.AUTONOME,
                        "ENIGME", NiveauCompetence.AUTONOME,
                        "ADRESSE", NiveauCompetence.AUTONOME,
                        "ROLE", NiveauCompetence.AUTONOME));
        verify("limiterTypologiesDistinctesParAnimateur")
                .given(
                        poste(standWithStrategy("STAND-STRAT-5"), creneauMatin, a1),
                        poste(stand("STAND-AMBIANCE-5", false, "AMBIANCE"), creneauAprem, a1),
                        poste(stand("STAND-ENIGME-5", false, "ENIGME"), matin("J2-MATIN5", 2, D2), a1),
                        poste(stand("STAND-ADRESSE-5", false, "ADRESSE"), afternoon("J2-AM5", 2, D2), a1),
                        poste(stand("STAND-ROLE-5", false, "ROLE"), matin("J3-MATIN5", 3, D3), a1),
                        new ParametresQualite())
                .penalizesBy(3);
    }

    /**
     * The threshold is a setting, not a constant (issue #591): the same three
     * typologies that cost a point at the default of 2 cost nothing to an
     * organiser for whom three is normal.
     */
    @Test
    void leSeuilDeTypologiesDistinctesEstUnParametre() {
        Animateur a1 = animateur(
                "A1",
                D1.minusYears(30),
                Map.of(
                        "STRATEGIE", NiveauCompetence.AUTONOME,
                        "AMBIANCE", NiveauCompetence.AUTONOME,
                        "ENIGME", NiveauCompetence.AUTONOME));
        ParametresQualite troisTypologiesNormales = new ParametresQualite(
                ParametresQualite.EMPLACEMENTS_DISTINCTS_PAR_JOUR_MAX_PAR_DEFAUT,
                ParametresQualite.HEURE_SERVICE_TARDIF_PAR_DEFAUT,
                ParametresQualite.HEURE_SERVICE_MATINAL_PAR_DEFAUT,
                ParametresQualite.REPOS_SOUHAITE_APRES_SERVICE_TARDIF_MINUTES_PAR_DEFAUT,
                3);

        verify("limiterTypologiesDistinctesParAnimateur")
                .given(
                        poste(standWithStrategy("STAND-STRAT-P"), creneauMatin, a1),
                        poste(stand("STAND-AMBIANCE-P", false, "AMBIANCE"), creneauAprem, a1),
                        poste(stand("STAND-ENIGME-P", false, "ENIGME"), matin("J2-MATINP", 2, D2), a1),
                        troisTypologiesNormales)
                .penalizesBy(0);
    }

    /**
     * The cap is the <b>edition's</b>, not the day's — issue #591 asked for
     * this to be nailed down rather than corrected, the {@code groupBy} having
     * always carried the animateur alone. Three typologies held in one single
     * day and three held across three days must cost the same point: a future
     * refactor that slipped a date key into that grouping would make the second
     * case free, and nothing else would notice.
     */
    @Test
    void leSeuilDeTypologiesDistinctesPorteSurLEditionEtPasSurLaJournee() {
        Animateur memeJour = animateur(
                "A-JOUR",
                D1.minusYears(30),
                Map.of(
                        "STRATEGIE", NiveauCompetence.AUTONOME,
                        "AMBIANCE", NiveauCompetence.AUTONOME,
                        "ENIGME", NiveauCompetence.AUTONOME));
        Animateur troisJours = animateur(
                "A-SEMAINE",
                D1.minusYears(30),
                Map.of(
                        "STRATEGIE", NiveauCompetence.AUTONOME,
                        "AMBIANCE", NiveauCompetence.AUTONOME,
                        "ENIGME", NiveauCompetence.AUTONOME));

        verify("limiterTypologiesDistinctesParAnimateur")
                .given(
                        poste(standWithStrategy("STAND-STRAT-J"), matin("J1-M-J", 1, D1), memeJour),
                        poste(stand("STAND-AMBIANCE-J", false, "AMBIANCE"), afternoon("J1-AM-J", 1, D1), memeJour),
                        poste(stand("STAND-ENIGME-J", false, "ENIGME"), nuit("J1-SOIR-J", 1, D1), memeJour),
                        new ParametresQualite())
                .penalizesBy(1);

        verify("limiterTypologiesDistinctesParAnimateur")
                .given(
                        poste(standWithStrategy("STAND-STRAT-S"), matin("J1-M-S", 1, D1), troisJours),
                        poste(stand("STAND-AMBIANCE-S", false, "AMBIANCE"), matin("J2-M-S", 2, D2), troisJours),
                        poste(stand("STAND-ENIGME-S", false, "ENIGME"), matin("J3-M-S", 3, D3), troisJours),
                        new ParametresQualite())
                .penalizesBy(1);
    }

    @Test
    void polyvalentNEstPasPenalisePourSesTypologiesDistinctes() {
        // Being spread across many typologies is exactly a ninja's job, so the
        // cap that penalises three distinct typologies doesn't apply to them.
        Animateur polyvalent = ninja("N1");
        polyvalent.getCompetences().put("STRATEGIE", NiveauCompetence.AUTONOME);
        polyvalent.getCompetences().put("AMBIANCE", NiveauCompetence.AUTONOME);
        polyvalent.getCompetences().put("ENIGME", NiveauCompetence.AUTONOME);
        verify("limiterTypologiesDistinctesParAnimateur")
                .given(
                        poste(standWithStrategy("STAND-STRAT-N"), creneauMatin, polyvalent),
                        poste(stand("STAND-AMBIANCE-N", false, "AMBIANCE"), creneauAprem, polyvalent),
                        poste(stand("STAND-ENIGME-N", false, "ENIGME"), matin("J2-MATIN-N", 2, D2), polyvalent))
                .penalizesBy(0);
    }

    @Test
    void polyvalentSansCompetenceSurLeStandNEstPasPenalise() {
        // A ninja adapts to any stand: no "appréciation" mismatch even on a
        // typologie they hold no competence for.
        verify("appreciationIncompatible")
                .given(poste(standStrat, creneauMatin, ninja("N1")))
                .penalizesBy(0);
    }

    // --- maxJoursConsecutifsTravailles --------------------------------------

    /** A short timeslot (9:00 - 13:00) on day {@code offset + 1}, offset calendar days after D1. */
    private Creneau jourConsecutif(int offset) {
        return creneau("JC-" + offset, offset + 1, D1.plusDays(offset), LocalTime.of(9, 0), LocalTime.of(13, 0));
    }

    @Test
    void septJoursConsecutifsTravaillesEstPenalise() {
        Animateur a1 = referentMajeur("A1");
        Object[] postes = new Object[7];
        for (int i = 0; i < 7; i++) {
            postes[i] = poste(standStrat, jourConsecutif(i), a1);
        }
        verify("maxJoursConsecutifsTravailles").given(postes).penalizesBy(1);
    }

    @Test
    void sixJoursConsecutifsTravaillesNEstPasPenalise() {
        Animateur a1 = referentMajeur("A1");
        Object[] postes = new Object[6];
        for (int i = 0; i < 6; i++) {
            postes[i] = poste(standStrat, jourConsecutif(i), a1);
        }
        verify("maxJoursConsecutifsTravailles").given(postes).penalizesBy(0);
    }

    @Test
    void sixJoursTravaillesUnJourDeReposPuisSixJoursNEstPasPenalise() {
        // A rest day resets the run: 6 + 6 with a gap between must not be
        // confused with 12 (or even 7) days in a row.
        Animateur a1 = referentMajeur("A1");
        Object[] postes = new Object[12];
        int index = 0;
        for (int i = 0; i < 6; i++) {
            postes[index++] = poste(standStrat, jourConsecutif(i), a1);
        }
        // offset 6 (day 7) is a rest day: deliberately skipped.
        for (int i = 7; i < 13; i++) {
            postes[index++] = poste(standStrat, jourConsecutif(i), a1);
        }
        verify("maxJoursConsecutifsTravailles").given(postes).penalizesBy(0);
    }

    /* ------------------ eviterFermeturePuisOuverture (#78) ------------------ */
    //
    // Thresholds travel as a ParametresQualite problem fact, so each test states
    // the ones it exercises instead of depending on the configured defaults.
    // ANTI_CLOPENING repeats those defaults on purpose: 22:00, 10:00, 12 h — the
    // very case the issue was opened on (closing at 23:00, restarting at 10:00).
    //
    // Reference arithmetic for an adult, whose legal floor is 11 h (660 min):
    // the rule prices only what the wished 12 h (720 min) asks BEYOND that
    // floor, so its largest possible penalty here is 60.

    private static final ParametresQualite ANTI_CLOPENING = new ParametresQualite(
            3,
            LocalTime.of(22, 0),
            LocalTime.of(10, 0),
            12 * 60,
            ParametresQualite.TYPOLOGIES_DISTINCTES_MAX_PAR_DEFAUT);

    private PosteAffectation vacation(String id, int jour, java.time.LocalDate date, int debut, int fin, Animateur a) {
        return poste(standStrat, creneau(id, jour, date, LocalTime.of(debut, 0), LocalTime.of(fin, 0)), a);
    }

    @Test
    void fermerTardPuisOuvrirTotEstPenaliseParLesMinutesManquantAuReposSouhaite() {
        // 23:00 -> 10:00 = 11 h: lawful to the minute, and exactly the pattern
        // the rule exists to flag. 720 wished - 660 legal floor = 60.
        Animateur a1 = majeurAutonome("A1");
        verify("eviterFermeturePuisOuverture")
                .given(ANTI_CLOPENING, vacation("J1-SOIR", 1, D1, 18, 23, a1), vacation("J2-MATIN", 2, D2, 10, 14, a1))
                .penalizesBy(60);
    }

    @Test
    void leMemeEnchainementNeViolePasLeReposQuotidienLegal() {
        // The counterpart of the test above: the hard rule sees nothing, which
        // is precisely what makes the soft one worth having.
        Animateur a1 = majeurAutonome("A1");
        verify("reposQuotidienMinimal")
                .given(vacation("J1-SOIR", 1, D1, 18, 23, a1), vacation("J2-MATIN", 2, D2, 10, 14, a1))
                .penalizesBy(0);
    }

    @Test
    void fermerTardDeuxSoirsDeSuiteNEstPasPenalise() {
        // late -> late: the restart is not an early one, so nothing is owed.
        Animateur a1 = majeurAutonome("A1");
        verify("eviterFermeturePuisOuverture")
                .given(ANTI_CLOPENING, vacation("J1-SOIR", 1, D1, 18, 23, a1), vacation("J2-SOIR", 2, D2, 18, 23, a1))
                .penalizesBy(0);
    }

    @Test
    void ouvrirDeuxMatinsDeSuiteNEstPasPenalise() {
        // early -> early: the day before does not close, so nothing is owed either.
        Animateur a1 = majeurAutonome("A1");
        verify("eviterFermeturePuisOuverture")
                .given(ANTI_CLOPENING, vacation("J1-MATIN", 1, D1, 8, 12, a1), vacation("J2-MATIN", 2, D2, 8, 12, a1))
                .penalizesBy(0);
    }

    @Test
    void fermerTardPuisOuvrirAvecLeReposSouhaiteNEstPasPenalise() {
        // 22:00 -> 10:00 = 12 h: the wished rest is reached, to the minute.
        Animateur a1 = majeurAutonome("A1");
        verify("eviterFermeturePuisOuverture")
                .given(ANTI_CLOPENING, vacation("J1-SOIR", 1, D1, 18, 22, a1), vacation("J2-MATIN", 2, D2, 10, 14, a1))
                .penalizesBy(0);
    }

    @Test
    void uneVacationQuiFranchitMinuitCompteCommeUnServiceTardif() {
        // 20:00 -> 00:00: read as a clock time (00:00) this vacation would pass
        // for an early one. Read as an instant, it does close day 1. The gap
        // 00:00 -> 10:00 is 10 h, under the legal floor, and the rule still
        // prices only the 60 minutes standing above that floor.
        Animateur a1 = majeurAutonome("A1");
        verify("eviterFermeturePuisOuverture")
                .given(
                        ANTI_CLOPENING,
                        poste(standStrat, nuit("J1-NUIT", 1, D1), a1),
                        vacation("J2-MATIN", 2, D2, 10, 14, a1))
                .penalizesBy(60);
    }

    @Test
    void leReposSousLePlancherLegalResteCompteParLaRegleDure() {
        // 23:00 -> 08:00 = 9 h. The soft rule bills 720 - 660 = 60, never
        // 720 - 540: the minutes under the floor belong to the hard rule, which
        // bills them in full (660 - 540 = 120).
        Animateur a1 = majeurAutonome("A1");
        verify("eviterFermeturePuisOuverture")
                .given(ANTI_CLOPENING, vacation("J1-SOIR", 1, D1, 18, 23, a1), vacation("J2-TOT", 2, D2, 8, 12, a1))
                .penalizesBy(60);

        verify("reposQuotidienMinimal")
                .given(vacation("J1-SOIR", 1, D1, 18, 23, a1), vacation("J2-TOT", 2, D2, 8, 12, a1))
                .penalizesBy(120);
    }

    @Test
    void laRegleEstMuettePourUnMineurDontLaLoiExigeDejaAutant() {
        // A 16-to-18 minor is owed 12 h of daily rest: the wish adds nothing to
        // that, and the soft rule goes entirely silent. What is missing here —
        // 60 minutes — is caught by reposQuotidienMinimal, hard.
        Animateur mineur = mineurDebutant("M1");
        verify("eviterFermeturePuisOuverture")
                .given(
                        ANTI_CLOPENING,
                        vacation("J1-SOIR", 1, D1, 18, 22, mineur),
                        vacation("J2-MATIN", 2, D2, 9, 13, mineur))
                .penalizesBy(0);

        verify("reposQuotidienMinimal")
                .given(vacation("J1-SOIR", 1, D1, 18, 22, mineur), vacation("J2-MATIN", 2, D2, 9, 13, mineur))
                .penalizesBy(60);
    }

    @Test
    void plusieursReprisesMatinalesNeFacturentQuUneNuit() {
        // The bug a pairwise join shipped: the rest of one night is a single
        // quantity, and billing it once per (late, early) couple charged 120
        // for the 60 minutes actually missing. Dropping the 10:00 seat must not
        // halve the penalty either — the animateur still restarts at 08:00.
        Animateur a1 = majeurAutonome("A1");
        verify("eviterFermeturePuisOuverture")
                .given(
                        ANTI_CLOPENING,
                        vacation("J1-SOIR", 1, D1, 18, 23, a1),
                        vacation("J2-TOT", 2, D2, 8, 10, a1),
                        vacation("J2-MATIN", 2, D2, 10, 14, a1))
                .penalizesBy(60);
    }

    @Test
    void unSoirCoupeEnDeuxNeFactureQuUneNuit() {
        // The same on the other side: only the last vacation of the evening
        // decides when the day closed, so a split closing pays once.
        Animateur a1 = majeurAutonome("A1");
        verify("eviterFermeturePuisOuverture")
                .given(
                        ANTI_CLOPENING,
                        vacation("J1-DEBUT-SOIR", 1, D1, 18, 22, a1),
                        vacation("J1-FIN-SOIR", 1, D1, 22, 23, a1),
                        vacation("J2-MATIN", 2, D2, 10, 14, a1))
                .penalizesBy(60);
    }

    @Test
    void uneJourneeQuiSeTermineTotNeFermePasTard() {
        // The day's LAST vacation is what closes it: an early one ending after
        // 22:00 would be a contradiction, but a late-afternoon shift following
        // a long morning must not be read through the morning's own end.
        Animateur a1 = majeurAutonome("A1");
        verify("eviterFermeturePuisOuverture")
                .given(
                        ANTI_CLOPENING,
                        vacation("J1-MATIN", 1, D1, 8, 12, a1),
                        vacation("J1-APREM", 1, D1, 14, 19, a1),
                        vacation("J2-MATIN", 2, D2, 8, 12, a1))
                .penalizesBy(0);
    }

    @Test
    void deuxJourneesNonConsecutivesNeSontJamaisAppariees() {
        Animateur a1 = majeurAutonome("A1");
        verify("eviterFermeturePuisOuverture")
                .given(ANTI_CLOPENING, vacation("J1-SOIR", 1, D1, 18, 23, a1), vacation("J3-MATIN", 3, D3, 10, 14, a1))
                .penalizesBy(0);
    }

    @Test
    void deuxAnimateursDifferentsNeSontJamaisApparies() {
        verify("eviterFermeturePuisOuverture")
                .given(
                        ANTI_CLOPENING,
                        vacation("J1-SOIR", 1, D1, 18, 23, majeurAutonome("A1")),
                        vacation("J2-MATIN", 2, D2, 10, 14, majeurAutonome("A2")))
                .penalizesBy(0);
    }

    @Test
    void desSiegesNonPourvusNeSontJamaisApparies() {
        // Two empty seats share a null animateur: without the guard the join
        // would pair them and the rule would grade an unsolved plan.
        verify("eviterFermeturePuisOuverture")
                .given(
                        ANTI_CLOPENING,
                        vacation("J1-SOIR", 1, D1, 18, 23, null),
                        vacation("J2-MATIN", 2, D2, 10, 14, null))
                .penalizesBy(0);
    }

    @Test
    void uneHeureDeSeuilAbsenteRendLaRegleInerte() {
        Animateur a1 = majeurAutonome("A1");
        ParametresQualite sansSeuilTardif = new ParametresQualite(
                3, null, LocalTime.of(10, 0), 12 * 60, ParametresQualite.TYPOLOGIES_DISTINCTES_MAX_PAR_DEFAUT);
        ParametresQualite sansSeuilMatinal = new ParametresQualite(
                3, LocalTime.of(22, 0), null, 12 * 60, ParametresQualite.TYPOLOGIES_DISTINCTES_MAX_PAR_DEFAUT);

        verify("eviterFermeturePuisOuverture")
                .given(sansSeuilTardif, vacation("J1-SOIR", 1, D1, 18, 23, a1), vacation("J2-MATIN", 2, D2, 10, 14, a1))
                .penalizesBy(0);
        verify("eviterFermeturePuisOuverture")
                .given(
                        sansSeuilMatinal,
                        vacation("J1-SOIR", 1, D1, 18, 23, a1),
                        vacation("J2-MATIN", 2, D2, 10, 14, a1))
                .penalizesBy(0);
    }

    @Test
    void unReposSouhaiteSousLePlancherLegalRendLaRegleInerte() {
        // 10 h wished where the law demands 11: there is nothing left to ask
        // for, and the rule must above all not become a second floor.
        Animateur a1 = majeurAutonome("A1");
        ParametresQualite souhaitTropBas = new ParametresQualite(
                3,
                LocalTime.of(22, 0),
                LocalTime.of(10, 0),
                10 * 60,
                ParametresQualite.TYPOLOGIES_DISTINCTES_MAX_PAR_DEFAUT);
        verify("eviterFermeturePuisOuverture")
                .given(souhaitTropBas, vacation("J1-SOIR", 1, D1, 18, 23, a1), vacation("J2-MATIN", 2, D2, 10, 14, a1))
                .penalizesBy(0);
    }

    @Test
    void unReposSouhaiteNulRendLaRegleInerte() {
        Animateur a1 = majeurAutonome("A1");
        ParametresQualite desactivee = new ParametresQualite(
                3, LocalTime.of(22, 0), LocalTime.of(10, 0), 0, ParametresQualite.TYPOLOGIES_DISTINCTES_MAX_PAR_DEFAUT);
        verify("eviterFermeturePuisOuverture")
                .given(desactivee, vacation("J1-SOIR", 1, D1, 18, 23, a1), vacation("J2-MATIN", 2, D2, 10, 14, a1))
                .penalizesBy(0);
    }
}
