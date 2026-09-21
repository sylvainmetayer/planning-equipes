package dev.sylvain.planning.solver.constraints;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ConstraintToggle;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.Stand;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;

class LegalConstraintsTest extends ConstraintTestBase {

    private final Stand standStrat = standWithStrategy("STAND-STRAT");
    private final Stand standMajeurs = stand("STAND-MAJ", true, "STRATEGIE");
    private final Creneau creneauMatin = matin("J1-MATIN", 1, D1);
    private final Creneau creneauNuit = nuit("J1-NUIT", 1, D1);
    private final Creneau matinJ2 = matin("J2-MATIN", 2, D2);
    private final Creneau apremJ2 = afternoon("J2-AM", 2, D2);

    @Test
    void mineurSurStandReserveAuxMajeursEstPenalise() {
        verify("standReserveAuxMajeurs")
                .given(poste(standMajeurs, creneauMatin, mineurDebutant("M1")))
                .penalizesBy(ExclusionEligibilite.FORFAIT);
    }

    @Test
    void majeurSurStandReserveAuxMajeursNEstPasPenalise() {
        verify("standReserveAuxMajeurs")
                .given(poste(standMajeurs, creneauMatin, referentMajeur("A1")))
                .penalizesBy(0);
    }

    /**
     * The catalogue ships {@code mineurNecessiteEncadrementMajeur} switched
     * off (issue #595): the organiser's managers, never planned, provide
     * that supervision, and no article of the Code du travail asks the solver
     * for it. An organiser without such a manager turns it on, which is what
     * the {@code actif = true} toggle below says — and what these two tests
     * cover, the rule itself rather than whether it applies by default.
     */
    private static final ConstraintToggle ENCADREMENT_DEMANDE =
            new ConstraintToggle("mineurNecessiteEncadrementMajeur", true);

    @Test
    void mineurSansMajeurSurLeStandEstPenaliseQuandLEncadrementEstDemande() {
        verify("mineurNecessiteEncadrementMajeur")
                .given(poste(standStrat, creneauMatin, mineurDebutant("M1")), ENCADREMENT_DEMANDE)
                .penalizesBy(ExclusionEligibilite.FORFAIT);
    }

    @Test
    void mineurEncadreParUnMajeurNEstPasPenalise() {
        verify("mineurNecessiteEncadrementMajeur")
                .given(
                        poste(standStrat, creneauMatin, mineurDebutant("M1")),
                        poste(standStrat, creneauMatin, referentMajeur("A1")),
                        ENCADREMENT_DEMANDE)
                .penalizesBy(0);
    }

    /**
     * The non-regression the decision of issue #595 is worth: with nothing
     * switched on, a minor holds a day stand on their own — and still cannot
     * take a night slot. Both halves in one test, because it is the pair that
     * was decided, and reading one without the other is how « the supervision
     * rule went » turns into « the minors' framework went ».
     */
    @Test
    void unMineurSeulTientUnStandDeJourMaisJamaisUnCreneauDeNuit() {
        verify("mineurNecessiteEncadrementMajeur")
                .given(poste(standStrat, creneauMatin, mineurDebutant("M1")))
                .penalizesBy(0);

        verify("travailDeNuitInterditPourMineur")
                .given(poste(standStrat, creneauNuit, mineurDebutant("M1")))
                .penalizesBy(ExclusionEligibilite.FORFAIT);

        verify("standReserveAuxMajeurs")
                .given(poste(standMajeurs, creneauMatin, mineurDebutant("M1")))
                .penalizesBy(ExclusionEligibilite.FORFAIT);
    }

    @Test
    void mineurTravaillantLaNuitEstPenalise() {
        verify("travailDeNuitInterditPourMineur")
                .given(poste(standStrat, creneauNuit, mineurDebutant("M1")))
                .penalizesBy(ExclusionEligibilite.FORFAIT);
    }

    @Test
    void majeurTravaillantLaNuitNEstPasPenalise() {
        verify("travailDeNuitInterditPourMineur")
                .given(poste(standStrat, creneauNuit, referentMajeur("A1")))
                .penalizesBy(0);
    }

    // --- Art. L3163-1: the night window depends on the age bracket ---------

    @Test
    void mineurDeMoinsDe16AnsEstPenaliseDes20Heures() {
        // 20:00-22:00: night work for an under-16 (art. L3163-1).
        Creneau soiree = creneau("J1-SOIREE", 1, D1, LocalTime.of(20, 0), LocalTime.of(22, 0));
        verify("travailDeNuitInterditPourMineur")
                .given(poste(standStrat, soiree, under16DebutantMineur("M15")))
                .penalizesBy(ExclusionEligibilite.FORFAIT);
    }

    @Test
    void mineurDe16A18AnsNEstPasPenaliseEntre20HEt22H() {
        // Same timeslot, 16-18: night only starts at 22:00 (art. L3163-1).
        // Before the fix, the 20:00 window was applied to every minor.
        Creneau soiree = creneau("J1-SOIREE", 1, D1, LocalTime.of(20, 0), LocalTime.of(22, 0));
        verify("travailDeNuitInterditPourMineur")
                .given(poste(standStrat, soiree, mineurDebutant("M17")))
                .penalizesBy(0);
    }

    @Test
    void mineurDe16A18AnsEstPenaliseApres22Heures() {
        Creneau tardive = creneau("J1-TARDIVE", 1, D1, LocalTime.of(21, 0), LocalTime.of(23, 0));
        verify("travailDeNuitInterditPourMineur")
                .given(poste(standStrat, tardive, mineurDebutant("M17")))
                .penalizesBy(ExclusionEligibilite.FORFAIT);
    }

    @Test
    void mineurDepassantHuitHeuresParJourEstPenalise() {
        // A 9 h timeslot is 510 min of travail effectif once the break it owes
        // comes off, 30 min above the 8 h ceiling (480 min).
        Creneau journee = longDay("J1-LONG", 1, D1);
        verify("dureeQuotidienneMaxMineur")
                .given(poste(standStrat, journee, mineurDebutant("M1")), new ParametresLegaux())
                .penalizesBy(ExclusionEligibilite.FORFAIT + 30);
    }

    @Test
    void mineurSousHuitHeuresParJourNEstPasPenalise() {
        verify("dureeQuotidienneMaxMineur")
                .given(poste(standStrat, creneauMatin, mineurDebutant("M1")), new ParametresLegaux())
                .penalizesBy(0);
    }

    @Test
    void mineurDeMoinsDe16AnsDepassantSeptHeuresParJourEstPenalise() {
        // Art. D4153-3: 7 h a day under 16. A 9 h timeslot is 8 h 30 of travail
        // effectif, 90 min over — where a 16-18 only exceeds by 30 min (8 h
        // ceiling, L3162-1).
        Creneau journee = longDay("J1-LONG", 1, D1);
        verify("dureeQuotidienneMaxMineur")
                .given(poste(standStrat, journee, under16DebutantMineur("M15")), new ParametresLegaux())
                .penalizesBy(ExclusionEligibilite.FORFAIT + 90);
    }

    @Test
    void mineurDeMoinsDe16AnsSousSeptHeuresParJourNEstPasPenalise() {
        Creneau sixHeures = creneau("J1-6H", 1, D1, LocalTime.of(9, 0), LocalTime.of(15, 0));
        verify("dureeQuotidienneMaxMineur")
                .given(poste(standStrat, sixHeures, under16DebutantMineur("M15")), new ParametresLegaux())
                .penalizesBy(0);
    }

    @Test
    void majeurNEstPasConcerneParLePlafondQuotidien() {
        Creneau journee = longDay("J1-LONG", 1, D1);
        verify("dureeQuotidienneMaxMineur")
                .given(poste(standStrat, journee, referentMajeur("A1")), new ParametresLegaux())
                .penalizesBy(0);
    }

    // --- Art. L3131-1 / L3164-1: minimum daily rest ------------------------

    @Test
    void majeurAvecMoinsDeOnzeHeuresDeReposEstPenalise() {
        // B1 of the audit, the exact case of scenario-complet.yaml: 20:00 → 00:00
        // then 08:00 → 12:00 the next day, that is 8 h of rest instead of 11 h
        // (L3131-1).
        Animateur majeur = referentMajeur("A1");
        Creneau matin8J2 = creneau("J2-8-12", 2, D2, LocalTime.of(8, 0), LocalTime.of(12, 0));
        verify("reposQuotidienMinimal")
                .given(poste(standStrat, creneauNuit, majeur), poste(standStrat, matin8J2, majeur))
                .penalizesBy(3 * 60);
    }

    @Test
    void majeurAvecOnzeHeuresDeReposNEstPasPenalise() {
        // 20:00 → 00:00 then 14:00 → 18:00 the next day: 14 h of rest.
        Animateur majeur = referentMajeur("A1");
        verify("reposQuotidienMinimal")
                .given(poste(standStrat, creneauNuit, majeur), poste(standStrat, apremJ2, majeur))
                .penalizesBy(0);
    }

    @Test
    void mineurDe16A18AnsExigeDouzeHeuresDeRepos() {
        // 09:00 → 13:00 then 00:30 → …: here 18:00 → 22:00 then 09:00 → 13:00,
        // that is 11 h of rest: compliant for an adult, 60 min too short for a
        // minor (12 h, art. L3164-1).
        Animateur mineur = mineurDebutant("M17");
        Creneau soirJ1 = creneau("J1-18-22", 1, D1, LocalTime.of(18, 0), LocalTime.of(22, 0));
        verify("reposQuotidienMinimal")
                .given(poste(standStrat, soirJ1, mineur), poste(standStrat, matinJ2, mineur))
                .penalizesBy(60);
    }

    @Test
    void mineurDeMoinsDe16AnsExigeQuatorzeHeuresDeRepos() {
        // Same pair of timeslots, under 16: 14 h required, 11 h obtained.
        Animateur mineur = under16DebutantMineur("M15");
        Creneau soirJ1 = creneau("J1-18-22", 1, D1, LocalTime.of(18, 0), LocalTime.of(22, 0));
        verify("reposQuotidienMinimal")
                .given(poste(standStrat, soirJ1, mineur), poste(standStrat, matinJ2, mineur))
                .penalizesBy(3 * 60);
    }

    @Test
    void deuxCreneauxDuMemeJourNeDeclenchentPasLeReposQuotidien() {
        Animateur majeur = referentMajeur("A1");
        verify("reposQuotidienMinimal")
                .given(poste(standStrat, creneauMatin, majeur), poste(standStrat, afternoon("J1-AM", 1, D1), majeur))
                .penalizesBy(0);
    }

    // --- Art. L3121-18: 10 h a day for an adult ----------------------------

    @Test
    void majeurDepassantDixHeuresParJourEstPenalise() {
        // B2 of the audit: the three timeslots of one day of scenario-complet
        // add up to 14 h of amplitude (4 + 6 + 4). The afternoon and the evening
        // are one ten-hour stretch owing one break, so 13 h 30 are worked —
        // 210 min above the ceiling.
        Animateur majeur = referentMajeur("A1");
        Creneau matin = creneau("J1-8-12", 1, D1, LocalTime.of(8, 0), LocalTime.of(12, 0));
        Creneau aprem = creneau("J1-14-20", 1, D1, LocalTime.of(14, 0), LocalTime.of(20, 0));
        Creneau soiree = creneau("J1-20-00", 1, D1, LocalTime.of(20, 0), LocalTime.of(0, 0));
        verify("dureeQuotidienneMaxMajeur")
                .given(
                        poste(standStrat, matin, majeur),
                        poste(standStrat, aprem, majeur),
                        poste(standStrat, soiree, majeur),
                        new ParametresLegaux())
                .penalizesBy(3 * 60 + 30);
    }

    @Test
    void majeurADixHeuresParJourNEstPasPenalise() {
        Animateur majeur = referentMajeur("A1");
        Creneau matin = creneau("J1-8-12", 1, D1, LocalTime.of(8, 0), LocalTime.of(12, 0));
        Creneau aprem = creneau("J1-14-20", 1, D1, LocalTime.of(14, 0), LocalTime.of(20, 0));
        verify("dureeQuotidienneMaxMajeur")
                .given(poste(standStrat, matin, majeur), poste(standStrat, aprem, majeur), new ParametresLegaux())
                .penalizesBy(0);
    }

    /**
     * Issue #60: a poste narrowed by a partial stand closure must count only
     * its effective minutes towards the daily cap, not the full créneau — a
     * 12 h créneau (6:00-18:00, 720 min) alone would breach the 10 h cap by
     * 120 min, but this poste only actually covers 6:00-12:00 (360 min).
     */
    @Test
    void fermeturePartielleNeCompteQueLaDureeEffectivePourLePlafondQuotidien() {
        Animateur majeur = referentMajeur("A1");
        Creneau longDay = creneau("J1-6-18", 1, D1, LocalTime.of(6, 0), LocalTime.of(18, 0));
        verify("dureeQuotidienneMaxMajeur")
                .given(
                        posteWithEffectiveFenetre(standStrat, longDay, majeur, LocalTime.of(6, 0), LocalTime.of(12, 0)),
                        new ParametresLegaux())
                .penalizesBy(0);
    }

    @Test
    void mineurNEstPasConcerneParLePlafondQuotidienMajeur() {
        Animateur mineur = mineurDebutant("M1");
        verify("dureeQuotidienneMaxMajeur")
                .given(poste(standStrat, longDay("J1-LONG", 1, D1), mineur), new ParametresLegaux())
                .penalizesBy(0);
    }

    // --- Art. L3121-16 / L3162-3: one rule, a hole or a relay (ADR 0048) ---
    //
    // A stretch over the cap owes breaks; each of them is taken as a hole in
    // the grid — which splits the stretch, so nothing is owed — or relayed by a
    // colleague of the same stand. One hard point per break nobody can take,
    // plus ExclusionEligibilite.FORFAIT for a minor. The edition's break is
    // thirty minutes by default (issue #31).

    @Test
    void dixHeuresDAffileeSansTrouNiRelaisDoiventUnePause() {
        // 14:00 → 20:00 then 20:00 → 00:00, no interruption: 10 h in one
        // stretch, which owes one break past the sixth hour. A1 holds the stand
        // alone, so nobody takes it.
        Animateur majeur = referentMajeur("A1");
        Creneau aprem = creneau("J1-14-20", 1, D1, LocalTime.of(14, 0), LocalTime.of(20, 0));
        Creneau soiree = creneau("J1-20-00", 1, D1, LocalTime.of(20, 0), LocalTime.of(0, 0));
        verify("travailContinuMaxMajeur")
                .given(poste(standStrat, aprem, majeur), poste(standStrat, soiree, majeur), new ParametresLegaux())
                .penalizesBy(1);
    }

    @Test
    void unTrouDeLaDureeDeLaPauseCoupeLaSequenceDuMajeur() {
        // Same day with a thirty-minute interruption: two stretches of 5 h 30
        // and 4 h, both under the cap, so nothing is owed and nothing needs a
        // relay.
        Animateur majeur = referentMajeur("A1");
        Creneau aprem = creneau("J1-14-1930", 1, D1, LocalTime.of(14, 0), LocalTime.of(19, 30));
        Creneau soiree = creneau("J1-20-00", 1, D1, LocalTime.of(20, 0), LocalTime.of(0, 0));
        verify("travailContinuMaxMajeur")
                .given(poste(standStrat, aprem, majeur), poste(standStrat, soiree, majeur), new ParametresLegaux())
                .penalizesBy(0);
    }

    @Test
    void unTrouPlusCourtQueLaPauseNeCoupePasLaSequenceDuMajeur() {
        // A twenty-minute interruption, under the thirty the edition grants:
        // the stretch stays continuous, measured 14:00 → 00:00, and owes its
        // break as if the gap were worked — which it is.
        Animateur majeur = referentMajeur("A1");
        Creneau aprem = creneau("J1-14-1940", 1, D1, LocalTime.of(14, 0), LocalTime.of(19, 40));
        Creneau soiree = creneau("J1-20-00", 1, D1, LocalTime.of(20, 0), LocalTime.of(0, 0));
        verify("travailContinuMaxMajeur")
                .given(poste(standStrat, aprem, majeur), poste(standStrat, soiree, majeur), new ParametresLegaux())
                .penalizesBy(1);
    }

    /**
     * The hole that cuts a stretch is the break the edition grants, not the
     * legal floor (issue #592): the same twenty-minute gap cuts it at twenty
     * and does not at thirty.
     */
    @Test
    void laDureeDuTrouQuiCoupeEstCelleQueLEditionAccorde() {
        Animateur majeur = referentMajeur("A1");
        Creneau aprem = creneau("J1-14-1940-P", 1, D1, LocalTime.of(14, 0), LocalTime.of(19, 40));
        Creneau soiree = creneau("J1-20-00-P", 1, D1, LocalTime.of(20, 0), LocalTime.of(0, 0));
        ParametresLegaux pauseDeVingt = new ParametresLegaux();
        pauseDeVingt.setDureePauseMinutes(20);

        verify("travailContinuMaxMajeur")
                .given(poste(standStrat, aprem, majeur), poste(standStrat, soiree, majeur), pauseDeVingt)
                .penalizesBy(0);
    }

    /**
     * Criterion 1 of issue #32, and the shape found on the 2026 edition: the
     * 13:00-14:00 meal relief followed by a full afternoon, seven hours in one
     * stretch, a break due at 19:00. Alone on the stand it costs a hard point;
     * with a colleague holding a place from before 19:00 to after 19:30 it
     * costs nothing.
     */
    @Test
    void unCollegueDuMemeStandPendantTouteLaPauseLaRelaie() {
        Animateur majeur = referentMajeur("A1");
        Animateur collegue = referentMajeur("A2");
        Creneau releve = creneau("J1-13-14", 1, D1, LocalTime.of(13, 0), LocalTime.of(14, 0));
        Creneau aprem = creneau("J1-14-20", 1, D1, LocalTime.of(14, 0), LocalTime.of(20, 0));

        verify("travailContinuMaxMajeur")
                .given(poste(standStrat, releve, majeur), poste(standStrat, aprem, majeur), new ParametresLegaux())
                .penalizesBy(1);
        verify("travailContinuMaxMajeur")
                .given(
                        poste(standStrat, releve, majeur),
                        poste(standStrat, aprem, majeur),
                        poste(standStrat, aprem, collegue),
                        new ParametresLegaux())
                .penalizesBy(0);
    }

    /** A colleague gone before the break is over has not relayed it. */
    @Test
    void unCollegueQuiPartAvantLaFinDeLaPauseNeLaRelaiePas() {
        Animateur majeur = referentMajeur("A1");
        Animateur collegue = referentMajeur("A2");
        Creneau releve = creneau("J1-13-14-B", 1, D1, LocalTime.of(13, 0), LocalTime.of(14, 0));
        Creneau aprem = creneau("J1-14-20-B", 1, D1, LocalTime.of(14, 0), LocalTime.of(20, 0));
        Creneau partiTot = creneau("J1-14-1915", 1, D1, LocalTime.of(14, 0), LocalTime.of(19, 15));

        verify("travailContinuMaxMajeur")
                .given(
                        poste(standStrat, releve, majeur),
                        poste(standStrat, aprem, majeur),
                        poste(standStrat, partiTot, collegue),
                        new ParametresLegaux())
                .penalizesBy(1);
    }

    /** A colleague on another stand is not a relay: the stand must stay held. */
    @Test
    void unCollegueSurUnAutreStandNeRelaiePas() {
        Animateur majeur = referentMajeur("A1");
        Animateur collegue = referentMajeur("A2");
        Creneau releve = creneau("J1-13-14-C", 1, D1, LocalTime.of(13, 0), LocalTime.of(14, 0));
        Creneau aprem = creneau("J1-14-20-C", 1, D1, LocalTime.of(14, 0), LocalTime.of(20, 0));

        verify("travailContinuMaxMajeur")
                .given(
                        poste(standStrat, releve, majeur),
                        poste(standStrat, aprem, majeur),
                        poste(standWithStrategy("STAND-AILLEURS"), aprem, collegue),
                        new ParametresLegaux())
                .penalizesBy(1);
    }

    /**
     * The penalty counts breaks, so it grows with the breach rather than
     * flattening at one: thirteen hours on end owe two.
     */
    @Test
    void treizeHeuresDAffileeDoiventDeuxPauses() {
        Animateur majeur = referentMajeur("A1");
        Creneau matin = creneau("J1-9-15", 1, D1, LocalTime.of(9, 0), LocalTime.of(15, 0));
        Creneau suite = creneau("J1-15-22", 1, D1, LocalTime.of(15, 0), LocalTime.of(22, 0));
        verify("travailContinuMaxMajeur")
                .given(poste(standStrat, matin, majeur), poste(standStrat, suite, majeur), new ParametresLegaux())
                .penalizesBy(2);
    }

    // --- The caps measure travail effectif: a break due is rest ------------

    @Test
    void lePlafondQuotidienDeduitLaPauseDue() {
        // 14:00-24:00: a 10 h amplitude owing one break of 30 min — 9 h 30 of
        // travail effectif, under the 10 h cap.
        Animateur majeur = referentMajeur("A1");
        Creneau aprem = creneau("J1-14-20", 1, D1, LocalTime.of(14, 0), LocalTime.of(20, 0));
        Creneau soiree = creneau("J1-20-00", 1, D1, LocalTime.of(20, 0), LocalTime.of(0, 0));
        verify("dureeQuotidienneMaxMajeur")
                .given(poste(standStrat, aprem, majeur), poste(standStrat, soiree, majeur), new ParametresLegaux())
                .penalizesBy(0);
    }

    @Test
    void lePlafondQuotidienResteDepasseQuandLaPauseDeduiteNeSuffitPas() {
        // 13:00-24:00: 11 h of amplitude. One break is enough for a stretch
        // under 12 h 30, so 10 h 30 of travail effectif remain — 30 min over.
        Animateur majeur = referentMajeur("A1");
        Creneau releve = creneau("J1-13-14", 1, D1, LocalTime.of(13, 0), LocalTime.of(14, 0));
        Creneau aprem = creneau("J1-14-20", 1, D1, LocalTime.of(14, 0), LocalTime.of(20, 0));
        Creneau soiree = creneau("J1-20-00", 1, D1, LocalTime.of(20, 0), LocalTime.of(0, 0));
        verify("dureeQuotidienneMaxMajeur")
                .given(
                        poste(standStrat, releve, majeur),
                        poste(standStrat, aprem, majeur),
                        poste(standStrat, soiree, majeur),
                        new ParametresLegaux())
                .penalizesBy(30);
    }

    @Test
    void unTrouLegalEntreDeuxVacationsNeFaitDeduireAucunePause() {
        // 09:00-13:00 then 14:00-20:00: the hour off is the break, no stretch
        // exceeds 6 h, nothing is owed and nothing is deducted — 10 h of
        // travail effectif, at the cap.
        Animateur majeur = referentMajeur("A1");
        Creneau matin = creneau("J1-9-13", 1, D1, LocalTime.of(9, 0), LocalTime.of(13, 0));
        Creneau aprem = creneau("J1-14-20", 1, D1, LocalTime.of(14, 0), LocalTime.of(20, 0));
        verify("dureeQuotidienneMaxMajeur")
                .given(poste(standStrat, matin, majeur), poste(standStrat, aprem, majeur), new ParametresLegaux())
                .penalizesBy(0);
        verify("travailContinuMaxMajeur")
                .given(poste(standStrat, matin, majeur), poste(standStrat, aprem, majeur), new ParametresLegaux())
                .penalizesBy(0);
    }

    /**
     * Criterion 3 of issue #32: a twenty-minute gap between two vacations of an
     * adult is penalised by nothing at all, and neither are two vacations that
     * touch. It used to cost ten hard points to {@code pauseMinimaleEntreVacations},
     * which contradicted the very break that ends a stretch — fourteen of the
     * fifteen shipped scenarios set that rule to zero to be rid of it.
     */
    @Test
    void unTrouDeVingtMinutesEntreDeuxVacationsNEstPenaliseParRien() {
        Animateur majeur = referentMajeur("A1");
        Creneau matin = creneau("J1-9-12", 1, D1, LocalTime.of(9, 0), LocalTime.of(12, 0));
        Creneau apres = creneau("J1-1220-14", 1, D1, LocalTime.of(12, 20), LocalTime.of(14, 0));
        Creneau colle = creneau("J1-14-15", 1, D1, LocalTime.of(14, 0), LocalTime.of(15, 0));

        verify("travailContinuMaxMajeur")
                .given(
                        poste(standStrat, matin, majeur),
                        poste(standStrat, apres, majeur),
                        poste(standStrat, colle, majeur),
                        new ParametresLegaux())
                .penalizesBy(0);
        verify("dureeQuotidienneMaxMajeur")
                .given(
                        poste(standStrat, matin, majeur),
                        poste(standStrat, apres, majeur),
                        poste(standStrat, colle, majeur),
                        new ParametresLegaux())
                .penalizesBy(0);
    }

    // --- Art. L3162-3: 4 h 30 of continuous work, at least 30 min ----------

    @Test
    void mineurSurUnCreneauDeSixHeuresSansRelaisEstPenalise() {
        // B7: the 14:00 → 20:00 timeslot of scenario-complet.yaml, held by a
        // minor alone: 6 h past a 4 h 30 cap, one break due and nobody to take
        // it. The flat cost of the minors' rules is kept (criterion 2).
        Animateur mineur = mineurDebutant("M1");
        Creneau aprem = creneau("J1-14-20", 1, D1, LocalTime.of(14, 0), LocalTime.of(20, 0));
        verify("travailContinuMaxMineur")
                .given(poste(standStrat, aprem, mineur), new ParametresLegaux())
                .penalizesBy(ExclusionEligibilite.FORFAIT + 1);
    }

    @Test
    void unCollegueRelaieAussiLaPauseDUnMineur() {
        Animateur mineur = mineurDebutant("M1");
        Animateur collegue = referentMajeur("A2");
        Creneau aprem = creneau("J1-14-20", 1, D1, LocalTime.of(14, 0), LocalTime.of(20, 0));
        verify("travailContinuMaxMineur")
                .given(poste(standStrat, aprem, mineur), poste(standStrat, aprem, collegue), new ParametresLegaux())
                .penalizesBy(0);
        // And the day counts 5 h 30 of travail effectif, under the 8 h cap.
        verify("dureeQuotidienneMaxMineur")
                .given(poste(standStrat, aprem, mineur), poste(standStrat, aprem, collegue), new ParametresLegaux())
                .penalizesBy(0);
    }

    @Test
    void mineurSurQuatreHeuresTrenteNEstPasPenalise() {
        Animateur mineur = mineurDebutant("M1");
        Creneau aprem = creneau("J1-14-1830", 1, D1, LocalTime.of(14, 0), LocalTime.of(18, 30));
        verify("travailContinuMaxMineur")
                .given(poste(standStrat, aprem, mineur), new ParametresLegaux())
                .penalizesBy(0);
    }

    @Test
    void unTrouDeTrenteMinutesCoupeLaSequenceDuMineur() {
        Animateur mineur = mineurDebutant("M1");
        Creneau debut = creneau("J1-9-13", 1, D1, LocalTime.of(9, 0), LocalTime.of(13, 0));
        Creneau suite = creneau("J1-1330-1700", 1, D1, LocalTime.of(13, 30), LocalTime.of(17, 0));
        verify("travailContinuMaxMineur")
                .given(poste(standStrat, debut, mineur), poste(standStrat, suite, mineur), new ParametresLegaux())
                .penalizesBy(0);
    }

    /**
     * Criterion 2 of issue #32: whatever the edition sets, a minor's break is
     * at least the thirty minutes of art. L3162-3, which is d'ordre public. An
     * edition granting twenty gives twenty to its adults — and a twenty-minute
     * hole cuts their stretch — while a minor's stretch runs on: 9:00 to 16:20
     * unbroken, one break due, nobody to relay it.
     */
    @Test
    void lePlancherDeTrenteMinutesDuMineurTientQuelleQueSoitLaDureeReglee() {
        Animateur mineur = mineurDebutant("M1");
        Animateur majeur = referentMajeur("A1");
        Creneau debut = creneau("J1-9-13", 1, D1, LocalTime.of(9, 0), LocalTime.of(13, 0));
        Creneau suite = creneau("J1-1320-1620", 1, D1, LocalTime.of(13, 20), LocalTime.of(16, 20));
        ParametresLegaux pauseDeVingt = new ParametresLegaux();
        pauseDeVingt.setDureePauseMinutes(20);

        verify("travailContinuMaxMineur")
                .given(poste(standStrat, debut, mineur), poste(standStrat, suite, mineur), pauseDeVingt)
                .penalizesBy(ExclusionEligibilite.FORFAIT + 1);
        verify("travailContinuMaxMajeur")
                .given(poste(standStrat, debut, majeur), poste(standStrat, suite, majeur), pauseDeVingt)
                .penalizesBy(0);
    }

    @Test
    void majeurNEstPasConcerneParLaLimiteDeTravailContinuDesMineurs() {
        Animateur majeur = referentMajeur("A1");
        Creneau aprem = creneau("J1-14-20", 1, D1, LocalTime.of(14, 0), LocalTime.of(20, 0));
        verify("travailContinuMaxMineur")
                .given(poste(standStrat, aprem, majeur), new ParametresLegaux())
                .penalizesBy(0);
    }

    // --- The weekly caps measure travail effectif too (issue #31) -----------

    /** A 14:00-24:00 vacation: ten hours of amplitude in one unbroken stretch. */
    private static Creneau dixHeures(String id, int jour, LocalDate date) {
        return creneau(id, jour, date, LocalTime.of(14, 0), LocalTime.of(0, 0));
    }

    /** The five days D1..D5, all inside 2026-W28, each a ten-hour stretch. */
    private java.util.List<Object> cinqJoursDixHeures(Animateur animateur) {
        return java.util.List.of(
                poste(standStrat, dixHeures("W-J1", 1, D1), animateur),
                poste(standStrat, dixHeures("W-J2", 2, D2), animateur),
                poste(standStrat, dixHeures("W-J3", 3, D3), animateur),
                poste(standStrat, dixHeures("W-J4", 4, D4), animateur),
                poste(standStrat, dixHeures("W-J5", 5, D5), animateur));
    }

    /**
     * Five ten-hour days in one week: 50 h of amplitude, 47 h 30 of travail
     * effectif once the five thirty-minute breaks come off — under the 48 h
     * ceiling, where the amplitude is two hours over.
     *
     * <p>This is the whole point of the change: the daily cap already read the
     * day that way, so the two rules used to give contradictory readings of the
     * same planning, and the weekly one refused plans the daily one allowed.</p>
     */
    @Test
    void lePlafondHebdomadaireDeduitLesPausesDues() {
        Animateur majeur = referentMajeur("A1");

        verify("dureeHebdomadaireMax")
                .given(concat(cinqJoursDixHeures(majeur), new ParametresLegaux()))
                .penalizesBy(0);
    }

    /**
     * The deduction is the edition's own break, not the legal floor: at twenty
     * minutes the same five days count 48 h 20 of travail effectif, twenty
     * minutes over.
     */
    @Test
    void laDeductionHebdomadaireLitLaDureeDePauseDeLEdition() {
        Animateur majeur = referentMajeur("A1");
        ParametresLegaux pauseDeVingt = new ParametresLegaux();
        pauseDeVingt.setDureePauseMinutes(20);

        verify("dureeHebdomadaireMax")
                .given(concat(cinqJoursDixHeures(majeur), pauseDeVingt))
                .penalizesBy(20);
    }

    /**
     * A real hole between two vacations is the break, so no stretch is over
     * six hours and nothing is deducted — at the week exactly as at the day.
     * Five days of 09:00-13:00 then 14:00-20:00 are ten worked hours each, two
     * hours over the ceiling.
     */
    @Test
    void unTrouLegalNeFaitRienDeduireAuNiveauHebdomadaire() {
        Animateur majeur = referentMajeur("A1");
        java.util.List<Object> semaineCoupee = new java.util.ArrayList<>();
        LocalDate[] jours = {D1, D2, D3, D4, D5};
        for (int i = 0; i < jours.length; i++) {
            semaineCoupee.add(poste(
                    standStrat, creneau("WC-M" + i, i + 1, jours[i], LocalTime.of(9, 0), LocalTime.of(13, 0)), majeur));
            semaineCoupee.add(poste(
                    standStrat,
                    creneau("WC-A" + i, i + 1, jours[i], LocalTime.of(14, 0), LocalTime.of(20, 0)),
                    majeur));
        }

        verify("dureeHebdomadaireMax")
                .given(concat(semaineCoupee, new ParametresLegaux()))
                .penalizesBy(2 * 60);
    }

    /**
     * The minors' weekly ceiling reads the same way: four 9 h days are 36 h of
     * amplitude, one hour over the 35 h of art. L3162-1 — and 34 h of travail
     * effectif once the four thirty-minute breaks come off.
     */
    @Test
    void lePlafondHebdomadaireDuMineurDeduitAussiLesPausesDues() {
        Animateur mineur = mineurDebutant("M1");
        java.util.List<Object> quatreLonguesJournees = java.util.List.of(
                poste(standStrat, longDay("WM-J1", 1, D1), mineur),
                poste(standStrat, longDay("WM-J2", 2, D2), mineur),
                poste(standStrat, longDay("WM-J3", 3, D3), mineur),
                poste(standStrat, longDay("WM-J4", 4, D4), mineur));

        verify("dureeHebdomadaireMaxMineur")
                .given(concat(quatreLonguesJournees, new ParametresLegaux()))
                .penalizesBy(0);
    }

    /** The facts of a match, plus the parameters that go with them. */
    private static Object[] concat(java.util.List<Object> postes, ParametresLegaux parametres) {
        Object[] facts = new Object[postes.size() + 1];
        for (int i = 0; i < postes.size(); i++) {
            facts[i] = postes.get(i);
        }
        facts[postes.size()] = parametres;
        return facts;
    }

    // --- Two consecutive weeks at the cap (issue #593) ---------------------

    /**
     * 2026-07-08 is a Wednesday, so D1 and D1+7 sit in two consecutive ISO
     * weeks. A cap of 480 min makes one full working day a full week, which
     * keeps the fixtures readable.
     */
    private static final LocalDate SEMAINE_SUIVANTE = D1.plusWeeks(1);

    private static final LocalDate DEUX_SEMAINES_PLUS_TARD = D1.plusWeeks(2);

    /**
     * Eight hours of <b>travail effectif</b>: 09:00-17:30 is 8 h 30 of
     * amplitude, one break due past the sixth hour, 8 h worked. The weeks are
     * judged on what is worked, so that is what the fixture has to carry — the
     * helper used to be a plain 09:00-17:00, which is a full week on amplitude
     * and 7 h 30 once the break comes off.
     */
    private static Creneau huitHeures(String id, LocalDate date) {
        return creneau(id, 1, date, LocalTime.of(9, 0), LocalTime.of(17, 30));
    }

    /** Seven hours of travail effectif: 09:00-16:30 less its thirty-minute break. */
    private static Creneau septHeures(String id, LocalDate date) {
        return creneau(id, 1, date, LocalTime.of(9, 0), LocalTime.of(16, 30));
    }

    @Test
    void deuxSemainesConsecutivesAuPlafondSontRefusees() {
        Animateur majeur = referentMajeur("A1");
        ParametresLegaux plafondHuitHeures = new ParametresLegaux(8 * 60);

        verify("dureeHebdomadaireMaxDeuxSemaines")
                .given(
                        poste(standStrat, huitHeures("S1", D1), majeur),
                        poste(standStrat, huitHeures("S2", SEMAINE_SUIVANTE), majeur),
                        plafondHuitHeures)
                .penalizesBy(1);
    }

    @Test
    void uneSemaineSousLePlafondSuivieDuneSemainePleineEstAcceptee() {
        Animateur majeur = referentMajeur("A1");
        ParametresLegaux plafondHuitHeures = new ParametresLegaux(8 * 60);

        verify("dureeHebdomadaireMaxDeuxSemaines")
                .given(
                        poste(standStrat, septHeures("S1", D1), majeur),
                        poste(standStrat, huitHeures("S2", SEMAINE_SUIVANTE), majeur),
                        plafondHuitHeures)
                .penalizesBy(0);
    }

    @Test
    void deuxSemainesPleinesNonConsecutivesSontAcceptees() {
        Animateur majeur = referentMajeur("A1");
        ParametresLegaux plafondHuitHeures = new ParametresLegaux(8 * 60);

        verify("dureeHebdomadaireMaxDeuxSemaines")
                .given(
                        poste(standStrat, huitHeures("S1", D1), majeur),
                        poste(standStrat, huitHeures("S3", DEUX_SEMAINES_PLUS_TARD), majeur),
                        plafondHuitHeures)
                .penalizesBy(0);
    }

    /** Three full weeks in a row are two pairs: the penalty grows with the breach. */
    @Test
    void troisSemainesPleinesConsecutivesCoutentDeuxPaires() {
        Animateur majeur = referentMajeur("A1");
        ParametresLegaux plafondHuitHeures = new ParametresLegaux(8 * 60);

        verify("dureeHebdomadaireMaxDeuxSemaines")
                .given(
                        poste(standStrat, huitHeures("S1", D1), majeur),
                        poste(standStrat, huitHeures("S2", SEMAINE_SUIVANTE), majeur),
                        poste(standStrat, huitHeures("S3", DEUX_SEMAINES_PLUS_TARD), majeur),
                        plafondHuitHeures)
                .penalizesBy(2);
    }

    /**
     * A week is judged full in travail effectif, like the ceiling it borrows
     * its threshold from. A plain 09:00-17:00 day is a full week under an 8 h
     * ceiling on amplitude, but only 7 h 30 once the break it owes comes off —
     * so two of them are not a pair. Half an hour more each week, and the
     * effective load is back at the ceiling and the pair is charged.
     */
    @Test
    void uneSemainePleineSeJugeEnTravailEffectif() {
        Animateur majeur = referentMajeur("A1");
        ParametresLegaux plafondHuitHeures = new ParametresLegaux(8 * 60);

        verify("dureeHebdomadaireMaxDeuxSemaines")
                .given(
                        poste(standStrat, creneau("E1", 1, D1, LocalTime.of(9, 0), LocalTime.of(17, 0)), majeur),
                        poste(
                                standStrat,
                                creneau("E2", 8, SEMAINE_SUIVANTE, LocalTime.of(9, 0), LocalTime.of(17, 0)),
                                majeur),
                        plafondHuitHeures)
                .penalizesBy(0);

        verify("dureeHebdomadaireMaxDeuxSemaines")
                .given(
                        poste(standStrat, huitHeures("E3", D1), majeur),
                        poste(standStrat, huitHeures("E4", SEMAINE_SUIVANTE), majeur),
                        plafondHuitHeures)
                .penalizesBy(1);
    }

    /** A minor has their own weekly ceiling (art. L3162-1); this rule is the adults'. */
    @Test
    void leMineurNEstPasConcerneParLaRegleDesDeuxSemaines() {
        Animateur mineur = mineurDebutant("M1");
        ParametresLegaux plafondHuitHeures = new ParametresLegaux(8 * 60);

        verify("dureeHebdomadaireMaxDeuxSemaines")
                .given(
                        poste(standStrat, huitHeures("S1", D1), mineur),
                        poste(standStrat, huitHeures("S2", SEMAINE_SUIVANTE), mineur),
                        plafondHuitHeures)
                .penalizesBy(0);
    }

    // --- Art. L3162-1: 35 h a week for a minor -----------------------------

    @Test
    void mineurDepassant35HeuresParSemaineEstPenalise() {
        // Four 9 h days in the same ISO week = 36 h, that is 60 min above the
        // public-order ceiling of 35 h (art. L3162-1).
        Animateur mineur = mineurDebutant("M1");
        verify("dureeHebdomadaireMaxMineur")
                .given(
                        poste(standStrat, longDay("J1-LONG", 1, D1), mineur),
                        poste(standStrat, longDay("J2-LONG", 2, D2), mineur),
                        poste(standStrat, longDay("J3-LONG", 3, D3), mineur),
                        poste(standStrat, longDay("J4-LONG", 4, D4), mineur),
                        poste(standStrat, creneau("J5-9-13", 5, D5, LocalTime.of(9, 0), LocalTime.of(13, 0)), mineur),
                        new ParametresLegaux())
                .penalizesBy(180);
    }

    @Test
    void mineurSousLes35HeuresParSemaineNEstPasPenalise() {
        Animateur mineur = mineurDebutant("M1");
        verify("dureeHebdomadaireMaxMineur")
                .given(
                        poste(standStrat, longDay("J1-LONG", 1, D1), mineur),
                        poste(standStrat, longDay("J2-LONG", 2, D2), mineur),
                        poste(standStrat, longDay("J3-LONG", 3, D3), mineur),
                        new ParametresLegaux())
                .penalizesBy(0);
    }

    @Test
    void majeurNEstPasConcerneParLePlafondHebdomadaireMineur() {
        Animateur majeur = referentMajeur("A1");
        verify("dureeHebdomadaireMaxMineur")
                .given(
                        poste(standStrat, longDay("J1-LONG", 1, D1), majeur),
                        poste(standStrat, longDay("J2-LONG", 2, D2), majeur),
                        poste(standStrat, longDay("J3-LONG", 3, D3), majeur),
                        poste(standStrat, longDay("J4-LONG", 4, D4), majeur),
                        new ParametresLegaux())
                .penalizesBy(0);
    }

    @Test
    void mineurNEstPlusSoumisAuPlafondHebdomadaireMajeur() {
        // Regression test for violation B3 of the audit: before the fix, a minor
        // was capped at 48 h by dureeHebdomadaireMax.
        Animateur mineur = mineurDebutant("M1");
        ParametresLegaux parametres = new ParametresLegaux(400);
        verify("dureeHebdomadaireMax")
                .given(poste(standStrat, creneauMatin, mineur), poste(standStrat, apremJ2, mineur), parametres)
                .penalizesBy(0);
    }

    // --- Art. L3164-6: public holidays -------------------------------------

    @Test
    void mineurTravaillantLeQuatorzeJuilletEstPenalise() {
        // B8: the bundled scenarios run in July 2026 and cover 14 July
        // (art. L3164-6, list of art. L3133-1).
        Creneau quatorzeJuillet =
                creneau("FETE-NAT", 7, java.time.LocalDate.of(2026, 7, 14), LocalTime.of(11, 0), LocalTime.of(15, 0));
        verify("travailInterditJourFerieMineur")
                .given(poste(standStrat, quatorzeJuillet, mineurDebutant("M1")))
                .penalizesBy(ExclusionEligibilite.FORFAIT);
    }

    @Test
    void majeurTravaillantLeQuatorzeJuilletNEstPasPenalise() {
        Creneau quatorzeJuillet =
                creneau("FETE-NAT", 7, java.time.LocalDate.of(2026, 7, 14), LocalTime.of(11, 0), LocalTime.of(15, 0));
        verify("travailInterditJourFerieMineur")
                .given(poste(standStrat, quatorzeJuillet, referentMajeur("A1")))
                .penalizesBy(0);
    }

    @Test
    void mineurTravaillantUnJourOrdinaireNEstPasPenalise() {
        verify("travailInterditJourFerieMineur")
                .given(poste(standStrat, creneauMatin, mineurDebutant("M1")))
                .penalizesBy(0);
    }

    // --- Art. L3132-1 / L3132-2 / L3164-2: weekly rest ---------------------

    /** A short timeslot (11:00 → 15:00) on day D of ISO week 2026-W29. */
    private Creneau jourSemaine29(int offsetDepuisLundi) {
        java.time.LocalDate lundi = java.time.LocalDate.of(2026, 7, 13);
        return creneau(
                "W29-J" + offsetDepuisLundi,
                6 + offsetDepuisLundi,
                lundi.plusDays(offsetDepuisLundi),
                LocalTime.of(11, 0),
                LocalTime.of(15, 0));
    }

    private Creneau jourSemaine30(int offsetDepuisLundi, LocalTime debut, LocalTime fin) {
        java.time.LocalDate lundi = java.time.LocalDate.of(2026, 7, 20);
        return creneau(
                "W30-J" + offsetDepuisLundi + "-" + debut,
                13 + offsetDepuisLundi,
                lundi.plusDays(offsetDepuisLundi),
                debut,
                fin);
    }

    @Test
    void septJoursTravaillesDansLaSemaineEstPenalise() {
        // B4: the event lasts 15 days; nothing stopped an animateur from being
        // assigned seven days in a row (art. L3132-1).
        Animateur majeur = referentMajeur("A1");
        Object[] postes = new Object[7];
        for (int i = 0; i < 7; i++) {
            postes[i] = poste(standStrat, jourSemaine29(i), majeur);
        }
        verify("maxJoursTravaillesParSemaine").given(postes).penalizesBy(1);
    }

    @Test
    void sixJoursTravaillesDansLaSemaineNEstPasPenalise() {
        Animateur majeur = referentMajeur("A1");
        Object[] postes = new Object[6];
        for (int i = 0; i < 6; i++) {
            postes[i] = poste(standStrat, jourSemaine29(i), majeur);
        }
        verify("maxJoursTravaillesParSemaine").given(postes).penalizesBy(0);
    }

    @Test
    void semaineSansTrenteCinqHeuresDeReposConsecutivesEstPenalisee() {
        // Seven days from 11:00 to 15:00. The rests between days are 20 h; the
        // best the week can be credited is the free time before Monday 11:00 —
        // 11 h of the week plus the 11 h of daily rest the law lets adjoin —
        // 22 h, that is 780 min under the 35 h minimum.
        Animateur majeur = referentMajeur("A1");
        Object[] postes = new Object[7];
        for (int i = 0; i < 7; i++) {
            postes[i] = poste(standStrat, jourSemaine29(i), majeur);
        }
        verify("reposHebdomadaireMinimal").given(postes).penalizesBy(35 * 60 - 22 * 60);
    }

    @Test
    void reposDuDimancheACheavalSurLeLundiCompteEnEntier() {
        // Six days from Monday 13 to Saturday 18 (11:00-15:00), Sunday off, six
        // days again from Monday 20. The rest runs from Saturday 15:00 to Monday
        // 11:00: 44 h, of which 33 h fall in week 29. Truncated at Monday 00:00 it
        // would be one hour short; the law reads it in full.
        Animateur majeur = referentMajeur("A1");
        Object[] postes = new Object[12];
        for (int i = 0; i < 6; i++) {
            postes[i] = poste(standStrat, jourSemaine29(i), majeur);
            postes[6 + i] = poste(standStrat, jourSemaine30(i, LocalTime.of(11, 0), LocalTime.of(15, 0)), majeur);
        }
        verify("reposHebdomadaireMinimal").given(postes).penalizesBy(0);
    }

    @Test
    void reposDuDimancheApresUneSoireeFinissantAMinuitResteInsuffisant() {
        // Same week, but Saturday 18 ends at midnight and Monday 20 starts at
        // 10:00: 34 consecutive hours, 24 h of Sunday plus 10 h of Monday — one
        // hour short of the 24 h + 11 h the law requires.
        Animateur majeur = referentMajeur("A1");
        Object[] postes = new Object[7];
        for (int i = 0; i < 5; i++) {
            postes[i] = poste(standStrat, jourSemaine29(i), majeur);
        }
        postes[5] = poste(
                standStrat,
                creneau("W29-SAM-NUIT", 11, LocalDate.of(2026, 7, 18), LocalTime.of(20, 0), LocalTime.of(0, 0)),
                majeur);
        postes[6] = poste(standStrat, jourSemaine30(0, LocalTime.of(10, 0), LocalTime.of(15, 0)), majeur);
        verify("reposHebdomadaireMinimal").given(postes).penalizesBy(60);
    }

    @Test
    void reposDuLundiCompteEnEntierPourLaSemaineQuiCommence() {
        // Sunday 12 worked until 20:00, Monday 13 off, Tuesday 14 from 09:00 then
        // five more days: the rest runs from Sunday 20:00 to Tuesday 09:00, 37 h,
        // of which only 33 h fall in week 29. Read in full, it satisfies the week.
        Animateur majeur = referentMajeur("A1");
        Object[] postes = new Object[7];
        postes[0] = poste(
                standStrat,
                creneau("W28-DIM", 5, LocalDate.of(2026, 7, 12), LocalTime.of(11, 0), LocalTime.of(20, 0)),
                majeur);
        postes[1] = poste(
                standStrat,
                creneau("W29-MAR-9H", 7, LocalDate.of(2026, 7, 14), LocalTime.of(9, 0), LocalTime.of(15, 0)),
                majeur);
        for (int i = 2; i < 7; i++) {
            postes[i] = poste(standStrat, jourSemaine29(i), majeur);
        }
        verify("reposHebdomadaireMinimal").given(postes).penalizesBy(0);
    }

    @Test
    void semaineAvecUnJourEtDemiDeReposNEstPasPenalisee() {
        // Work from Monday to Friday, then nothing: from Friday 15:00 to the
        // following Monday 00:00, well over 35 consecutive hours.
        Animateur majeur = referentMajeur("A1");
        Object[] postes = new Object[5];
        for (int i = 0; i < 5; i++) {
            postes[i] = poste(standStrat, jourSemaine29(i), majeur);
        }
        verify("reposHebdomadaireMinimal").given(postes).penalizesBy(0);
    }

    @Test
    void mineurSansDeuxJoursDeReposConsecutifsEstPenalise() {
        // Work on Monday, Tuesday, Thursday, Friday, Sunday: the free days
        // (Wednesday, Saturday) are never consecutive (art. L3164-2).
        Animateur mineur = mineurDebutant("M1");
        verify("reposHebdomadaireMineur")
                .given(
                        poste(standStrat, jourSemaine29(0), mineur),
                        poste(standStrat, jourSemaine29(1), mineur),
                        poste(standStrat, jourSemaine29(3), mineur),
                        poste(standStrat, jourSemaine29(4), mineur),
                        poste(standStrat, jourSemaine29(6), mineur))
                .penalizesBy(1);
    }

    @Test
    void mineurAvecDeuxJoursDeReposConsecutifsNEstPasPenalise() {
        // Work from Monday to Friday, Saturday and Sunday free.
        Animateur mineur = mineurDebutant("M1");
        verify("reposHebdomadaireMineur")
                .given(
                        poste(standStrat, jourSemaine29(0), mineur),
                        poste(standStrat, jourSemaine29(1), mineur),
                        poste(standStrat, jourSemaine29(2), mineur),
                        poste(standStrat, jourSemaine29(3), mineur),
                        poste(standStrat, jourSemaine29(4), mineur))
                .penalizesBy(0);
    }

    /**
     * A Sunday off followed by a Monday off is one free day in each of two
     * civil weeks, not two consecutive days of either: the right of art.
     * L3164-2 is « par semaine », and the week must hold the rest itself
     * (L3121-35; Cass. soc. 13 nov. 2025, n° 24-10.733). A minor working
     * Monday 13 → Saturday 18 July, then Tuesday 21 → Sunday 26, has worked
     * twelve days out of fourteen with two days off: both weeks are one day
     * short. Locked here because the opposite reading was once proposed and
     * would have quietly aligned minors on the adults' floor.
     */
    @Test
    void mineurAvecDimancheEtLundiLibresAChevalSurDeuxSemainesEstPenaliseSurChacune() {
        Animateur mineur = mineurDebutant("M1");
        Object[] postes = new Object[12];
        for (int i = 0; i < 6; i++) {
            postes[i] = poste(standStrat, jourSemaine29(i), mineur);
            postes[6 + i] = poste(standStrat, jourSemaine30(1 + i, LocalTime.of(11, 0), LocalTime.of(15, 0)), mineur);
        }
        verify("reposHebdomadaireMineur").given(postes).penalizesBy(1 + 1);
    }

    @Test
    void majeurNEstPasConcerneParLeReposHebdomadaireDesMineurs() {
        Animateur majeur = referentMajeur("A1");
        Object[] postes = new Object[7];
        for (int i = 0; i < 7; i++) {
            postes[i] = poste(standStrat, jourSemaine29(i), majeur);
        }
        verify("reposHebdomadaireMineur").given(postes).penalizesBy(0);
    }

    @Test
    void deuxAnimateursDistinctsNeSontPasCumulesEnsemble() {
        Animateur a1 = referentMajeur("A1");
        Animateur a2 = referentMajeur("A2");
        ParametresLegaux parametres = new ParametresLegaux(400);
        verify("dureeHebdomadaireMax")
                .given(poste(standStrat, creneauMatin, a1), poste(standStrat, apremJ2, a2), parametres)
                .penalizesBy(0);
    }

    /* ------------------- counted, never reproached (ADR 0044) ------------------- */

    @Test
    void thePastEveningCountsAgainstTheMorningStillAhead() {
        // 20:00 → 00:00 already worked, then 08:00 → 12:00 tomorrow: the 8 h
        // of rest are 3 h short, and tomorrow can still move.
        Animateur majeur = referentMajeur("A1");
        Creneau matin8J2 = creneau("J2-8-12", 2, D2, LocalTime.of(8, 0), LocalTime.of(12, 0));
        verify("reposQuotidienMinimal")
                .given(postePasse(standStrat, creneauNuit, majeur), poste(standStrat, matin8J2, majeur))
                .penalizesBy(3 * 60);
    }

    @Test
    void aShortRestBetweenTwoPastDaysIsHistory() {
        Animateur majeur = referentMajeur("A1");
        Creneau matin8J2 = creneau("J2-8-12", 2, D2, LocalTime.of(8, 0), LocalTime.of(12, 0));
        verify("reposQuotidienMinimal")
                .given(postePasse(standStrat, creneauNuit, majeur), postePasse(standStrat, matin8J2, majeur))
                .penalizesBy(0);
    }

    @Test
    void theHoursAlreadyWorkedCountInTheWeekStillOpen() {
        // A 400 min cap: 240 min already worked, 240 min still ahead in the
        // same ISO week — 80 min over, charged, because tomorrow can move.
        Animateur majeur = referentMajeur("A1");
        ParametresLegaux parametres = new ParametresLegaux(400);
        verify("dureeHebdomadaireMax")
                .given(postePasse(standStrat, creneauMatin, majeur), poste(standStrat, apremJ2, majeur), parametres)
                .penalizesBy(80);
        // The same week entirely worked: history.
        verify("dureeHebdomadaireMax")
                .given(
                        postePasse(standStrat, creneauMatin, majeur),
                        postePasse(standStrat, apremJ2, majeur),
                        parametres)
                .penalizesBy(0);
    }

    @Test
    void aPastDayOverTheDailyCapIsHistoryButTodayStillUnderWayIsCharged() {
        Animateur majeur = referentMajeur("A1");
        Creneau matin = creneau("J1-8-12", 1, D1, LocalTime.of(8, 0), LocalTime.of(12, 0));
        Creneau aprem = creneau("J1-13-19", 1, D1, LocalTime.of(13, 0), LocalTime.of(19, 0));
        Creneau soir = creneau("J1-19-23", 1, D1, LocalTime.of(19, 0), LocalTime.of(23, 0));
        verify("dureeQuotidienneMaxMajeur")
                .given(
                        postePasse(standStrat, matin, majeur),
                        postePasse(standStrat, aprem, majeur),
                        postePasse(standStrat, soir, majeur),
                        new ParametresLegaux())
                .penalizesBy(0);
        verify("dureeQuotidienneMaxMajeur")
                .given(
                        postePasse(standStrat, matin, majeur),
                        postePasse(standStrat, aprem, majeur),
                        poste(standStrat, soir, majeur),
                        new ParametresLegaux())
                .penalizesBy(3 * 60 + 30);
    }

    @Test
    void twoFullWeeksAreHistoryOnceWorkedAndStillAPairWhileOneIsOpenWithNoMinuteDropped() {
        // Each week reaches its 8 h in two 4 h seats: a merge that dropped
        // minutes would read 4 h and see no full week at all.
        Animateur majeur = referentMajeur("A1");
        ParametresLegaux plafondHuitHeures = new ParametresLegaux(8 * 60);
        verify("dureeHebdomadaireMaxDeuxSemaines")
                .given(
                        postePasse(standStrat, creneauMatin, majeur),
                        postePasse(standStrat, afternoon("J1-AM", 1, D1), majeur),
                        postePasse(standStrat, matin("S2-MATIN", 8, SEMAINE_SUIVANTE), majeur),
                        postePasse(standStrat, afternoon("S2-AM", 8, SEMAINE_SUIVANTE), majeur),
                        plafondHuitHeures)
                .penalizesBy(0);
        verify("dureeHebdomadaireMaxDeuxSemaines")
                .given(
                        postePasse(standStrat, creneauMatin, majeur),
                        postePasse(standStrat, afternoon("J1-AM", 1, D1), majeur),
                        postePasse(standStrat, matin("S2-MATIN", 8, SEMAINE_SUIVANTE), majeur),
                        poste(standStrat, afternoon("S2-AM", 8, SEMAINE_SUIVANTE), majeur),
                        plafondHuitHeures)
                .penalizesBy(1);
    }

    @Test
    void aWeekWithoutItsWeeklyRestIsHistoryOnceWorkedAndChargedWhileASeatIsAheadWithThePastCounted() {
        // Seven days 11:00-15:00: 780 min short (see the nominal case). All
        // past: nothing. The Sunday still ahead: the six past days shape the
        // rests all the same, and the same 780 min are charged.
        Animateur majeur = referentMajeur("A1");
        Object[] passes = new Object[7];
        Object[] enCours = new Object[7];
        for (int i = 0; i < 7; i++) {
            passes[i] = postePasse(standStrat, jourSemaine29(i), majeur);
            enCours[i] = i < 6
                    ? postePasse(standStrat, jourSemaine29(i), majeur)
                    : poste(standStrat, jourSemaine29(i), majeur);
        }
        verify("reposHebdomadaireMinimal").given(passes).penalizesBy(0);
        verify("reposHebdomadaireMinimal").given(enCours).penalizesBy(35 * 60 - 22 * 60);
    }

    @Test
    void sevenDaysWorkedAreHistoryButSixWorkedAndASeventhAheadAreCharged() {
        Animateur majeur = referentMajeur("A1");
        Object[] passes = new Object[7];
        Object[] enCours = new Object[7];
        for (int i = 0; i < 7; i++) {
            passes[i] = postePasse(standStrat, jourSemaine29(i), majeur);
            enCours[i] = i < 6
                    ? postePasse(standStrat, jourSemaine29(i), majeur)
                    : poste(standStrat, jourSemaine29(i), majeur);
        }
        verify("maxJoursTravaillesParSemaine").given(passes).penalizesBy(0);
        verify("maxJoursTravaillesParSemaine").given(enCours).penalizesBy(1);
    }
}
