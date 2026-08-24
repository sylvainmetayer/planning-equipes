package dev.sylvain.planning.solver.constraints;

import java.time.LocalTime;

import org.junit.jupiter.api.Test;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.Stand;

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
                .penalizesBy(1);
    }

    @Test
    void majeurSurStandReserveAuxMajeursNEstPasPenalise() {
        verify("standReserveAuxMajeurs")
                .given(poste(standMajeurs, creneauMatin, referentMajeur("A1")))
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
                        poste(standStrat, creneauMatin, referentMajeur("A1")))
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
                .penalizesBy(1);
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
                .penalizesBy(1);
    }

    @Test
    void mineurDepassantHuitHeuresParJourEstPenalise() {
        // A 9 h timeslot (540 min) exceeds the 8 h ceiling (480 min) by 60 min.
        Creneau journee = longDay("J1-LONG", 1, D1);
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
    void mineurDeMoinsDe16AnsDepassantSeptHeuresParJourEstPenalise() {
        // Art. D4153-3: 7 h a day under 16. A 9 h timeslot exceeds it by 120 min,
        // where a 16-18 would only exceed by 60 min (8 h ceiling, L3162-1).
        Creneau journee = longDay("J1-LONG", 1, D1);
        verify("dureeQuotidienneMaxMineur")
                .given(poste(standStrat, journee, under16DebutantMineur("M15")))
                .penalizesBy(120);
    }

    @Test
    void mineurDeMoinsDe16AnsSousSeptHeuresParJourNEstPasPenalise() {
        Creneau sixHeures = creneau("J1-6H", 1, D1, LocalTime.of(9, 0), LocalTime.of(15, 0));
        verify("dureeQuotidienneMaxMineur")
                .given(poste(standStrat, sixHeures, under16DebutantMineur("M15")))
                .penalizesBy(0);
    }

    @Test
    void majeurNEstPasConcerneParLePlafondQuotidien() {
        Creneau journee = longDay("J1-LONG", 1, D1);
        verify("dureeQuotidienneMaxMineur")
                .given(poste(standStrat, journee, referentMajeur("A1")))
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
                .given(poste(standStrat, creneauNuit, majeur),
                        poste(standStrat, matin8J2, majeur))
                .penalizesBy(3 * 60);
    }

    @Test
    void majeurAvecOnzeHeuresDeReposNEstPasPenalise() {
        // 20:00 → 00:00 then 14:00 → 18:00 the next day: 14 h of rest.
        Animateur majeur = referentMajeur("A1");
        verify("reposQuotidienMinimal")
                .given(poste(standStrat, creneauNuit, majeur),
                        poste(standStrat, apremJ2, majeur))
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
                .given(poste(standStrat, soirJ1, mineur),
                        poste(standStrat, matinJ2, mineur))
                .penalizesBy(60);
    }

    @Test
    void mineurDeMoinsDe16AnsExigeQuatorzeHeuresDeRepos() {
        // Same pair of timeslots, under 16: 14 h required, 11 h obtained.
        Animateur mineur = under16DebutantMineur("M15");
        Creneau soirJ1 = creneau("J1-18-22", 1, D1, LocalTime.of(18, 0), LocalTime.of(22, 0));
        verify("reposQuotidienMinimal")
                .given(poste(standStrat, soirJ1, mineur),
                        poste(standStrat, matinJ2, mineur))
                .penalizesBy(3 * 60);
    }

    @Test
    void deuxCreneauxDuMemeJourNeDeclenchentPasLeReposQuotidien() {
        Animateur majeur = referentMajeur("A1");
        verify("reposQuotidienMinimal")
                .given(poste(standStrat, creneauMatin, majeur),
                        poste(standStrat, afternoon("J1-AM", 1, D1), majeur))
                .penalizesBy(0);
    }

    // --- Art. L3121-18: 10 h a day for an adult ----------------------------

    @Test
    void majeurDepassantDixHeuresParJourEstPenalise() {
        // B2 of the audit: the three timeslots of one day of scenario-complet
        // add up to 14 h (4 + 6 + 4), that is 240 min above the ceiling.
        Animateur majeur = referentMajeur("A1");
        Creneau matin = creneau("J1-8-12", 1, D1, LocalTime.of(8, 0), LocalTime.of(12, 0));
        Creneau aprem = creneau("J1-14-20", 1, D1, LocalTime.of(14, 0), LocalTime.of(20, 0));
        Creneau soiree = creneau("J1-20-00", 1, D1, LocalTime.of(20, 0), LocalTime.of(0, 0));
        verify("dureeQuotidienneMaxMajeur")
                .given(poste(standStrat, matin, majeur),
                        poste(standStrat, aprem, majeur),
                        poste(standStrat, soiree, majeur))
                .penalizesBy(4 * 60);
    }

    @Test
    void majeurADixHeuresParJourNEstPasPenalise() {
        Animateur majeur = referentMajeur("A1");
        Creneau matin = creneau("J1-8-12", 1, D1, LocalTime.of(8, 0), LocalTime.of(12, 0));
        Creneau aprem = creneau("J1-14-20", 1, D1, LocalTime.of(14, 0), LocalTime.of(20, 0));
        verify("dureeQuotidienneMaxMajeur")
                .given(poste(standStrat, matin, majeur),
                        poste(standStrat, aprem, majeur))
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
                .given(posteWithEffectiveFenetre(standStrat, longDay, majeur,
                        LocalTime.of(6, 0), LocalTime.of(12, 0)))
                .penalizesBy(0);
    }

    @Test
    void mineurNEstPasConcerneParLePlafondQuotidienMajeur() {
        Animateur mineur = mineurDebutant("M1");
        verify("dureeQuotidienneMaxMajeur")
                .given(poste(standStrat, longDay("J1-LONG", 1, D1), mineur))
                .penalizesBy(0);
    }

    // --- Art. L3121-16: 6 h of continuous work / a 20 min break ------------

    @Test
    void majeurEnchainantDeuxCreneauxSansPauseSuffisanteEstPenalise() {
        // B6: 14:00 → 20:00 then 20:00 → 00:00, no interruption: 10 h in one
        // stretch, that is 240 min above the 6 h maximum.
        Animateur majeur = referentMajeur("A1");
        Creneau aprem = creneau("J1-14-20", 1, D1, LocalTime.of(14, 0), LocalTime.of(20, 0));
        Creneau soiree = creneau("J1-20-00", 1, D1, LocalTime.of(20, 0), LocalTime.of(0, 0));
        verify("travailContinuMaxMajeur")
                .given(poste(standStrat, aprem, majeur),
                        poste(standStrat, soiree, majeur))
                .penalizesBy(4 * 60);
    }

    @Test
    void unePauseDeVingtMinutesCoupeLaSequenceDuMajeur() {
        // Same day, but with a 20 minute interruption: two sequences of 5 h 40
        // and 4 h, both under the 6 h maximum.
        Animateur majeur = referentMajeur("A1");
        Creneau aprem = creneau("J1-14-1940", 1, D1, LocalTime.of(14, 0), LocalTime.of(19, 40));
        Creneau soiree = creneau("J1-20-00", 1, D1, LocalTime.of(20, 0), LocalTime.of(0, 0));
        verify("travailContinuMaxMajeur")
                .given(poste(standStrat, aprem, majeur),
                        poste(standStrat, soiree, majeur))
                .penalizesBy(0);
    }

    @Test
    void unePauseTropCourteNeCoupePasLaSequenceDuMajeur() {
        // A 10 minute interruption: the law requires 20 consecutive minutes, so
        // the sequence stays continuous (6 h 10 measured edge to edge).
        Animateur majeur = referentMajeur("A1");
        Creneau debut = creneau("J1-14-17", 1, D1, LocalTime.of(14, 0), LocalTime.of(17, 0));
        Creneau suite = creneau("J1-1710-2010", 1, D1, LocalTime.of(17, 10), LocalTime.of(20, 10));
        verify("travailContinuMaxMajeur")
                .given(poste(standStrat, debut, majeur),
                        poste(standStrat, suite, majeur))
                .penalizesBy(10);
    }

    // --- Art. L3162-3: 4 h 30 of continuous work / a 30 min break ----------

    @Test
    void mineurSurUnCreneauDeSixHeuresEstPenalise() {
        // B7: the 14:00 → 20:00 timeslot of scenario-complet.yaml, held by a
        // minor, exceeds the maximum continuous working time by 1 h 30.
        Animateur mineur = mineurDebutant("M1");
        Creneau aprem = creneau("J1-14-20", 1, D1, LocalTime.of(14, 0), LocalTime.of(20, 0));
        verify("travailContinuMaxMineur")
                .given(poste(standStrat, aprem, mineur))
                .penalizesBy(90);
    }

    @Test
    void mineurSurQuatreHeuresTrenteNEstPasPenalise() {
        Animateur mineur = mineurDebutant("M1");
        Creneau aprem = creneau("J1-14-1830", 1, D1, LocalTime.of(14, 0), LocalTime.of(18, 30));
        verify("travailContinuMaxMineur")
                .given(poste(standStrat, aprem, mineur))
                .penalizesBy(0);
    }

    @Test
    void unePauseDeTrenteMinutesCoupeLaSequenceDuMineur() {
        Animateur mineur = mineurDebutant("M1");
        Creneau debut = creneau("J1-9-13", 1, D1, LocalTime.of(9, 0), LocalTime.of(13, 0));
        Creneau suite = creneau("J1-1330-1700", 1, D1, LocalTime.of(13, 30), LocalTime.of(17, 0));
        verify("travailContinuMaxMineur")
                .given(poste(standStrat, debut, mineur),
                        poste(standStrat, suite, mineur))
                .penalizesBy(0);
    }

    @Test
    void unePauseDeVingtMinutesNeSuffitPasAUnMineur() {
        // 20 minutes are enough for an adult, not for a minor (30 min, L3162-3):
        // the sequence runs from 9:00 to 16:20, that is 7 h 20, hence 170 min too
        // many.
        Animateur mineur = mineurDebutant("M1");
        Creneau debut = creneau("J1-9-13", 1, D1, LocalTime.of(9, 0), LocalTime.of(13, 0));
        Creneau suite = creneau("J1-1320-1620", 1, D1, LocalTime.of(13, 20), LocalTime.of(16, 20));
        verify("travailContinuMaxMineur")
                .given(poste(standStrat, debut, mineur),
                        poste(standStrat, suite, mineur))
                .penalizesBy(170);
    }

    @Test
    void majeurNEstPasConcerneParLaLimiteDeTravailContinuDesMineurs() {
        Animateur majeur = referentMajeur("A1");
        Creneau aprem = creneau("J1-14-20", 1, D1, LocalTime.of(14, 0), LocalTime.of(20, 0));
        verify("travailContinuMaxMineur")
                .given(poste(standStrat, aprem, majeur))
                .penalizesBy(0);
    }

    @Test
    void depassementDureeHebdomadaireMaxEstPenalise() {
        // Same ISO week as D1/D2 (2026-07-08/09): two 4h slots (matin + aprem)
        // total 480 min, 80 min over a 400 min cap.
        Animateur majeur = referentMajeur("A1");
        ParametresLegaux parametres = new ParametresLegaux(400);
        verify("dureeHebdomadaireMax")
                .given(poste(standStrat, creneauMatin, majeur),
                        poste(standStrat, apremJ2, majeur),
                        parametres)
                .penalizesBy(80);
    }

    @Test
    void sousLaDureeHebdomadaireMaxNEstPasPenalise() {
        Animateur majeur = referentMajeur("A1");
        ParametresLegaux parametres = new ParametresLegaux(500);
        verify("dureeHebdomadaireMax")
                .given(poste(standStrat, creneauMatin, majeur),
                        poste(standStrat, apremJ2, majeur),
                        parametres)
                .penalizesBy(0);
    }

    // --- Art. L3162-1: 35 h a week for a minor -----------------------------

    @Test
    void mineurDepassant35HeuresParSemaineEstPenalise() {
        // Four 9 h days in the same ISO week = 36 h, that is 60 min above the
        // public-order ceiling of 35 h (art. L3162-1).
        Animateur mineur = mineurDebutant("M1");
        verify("dureeHebdomadaireMaxMineur")
                .given(poste(standStrat, longDay("J1-LONG", 1, D1), mineur),
                        poste(standStrat, longDay("J2-LONG", 2, D2), mineur),
                        poste(standStrat, longDay("J3-LONG", 3, D3), mineur),
                        poste(standStrat, longDay("J4-LONG", 4, D4), mineur),
                        new ParametresLegaux())
                .penalizesBy(60);
    }

    @Test
    void mineurSousLes35HeuresParSemaineNEstPasPenalise() {
        Animateur mineur = mineurDebutant("M1");
        verify("dureeHebdomadaireMaxMineur")
                .given(poste(standStrat, longDay("J1-LONG", 1, D1), mineur),
                        poste(standStrat, longDay("J2-LONG", 2, D2), mineur),
                        poste(standStrat, longDay("J3-LONG", 3, D3), mineur),
                        new ParametresLegaux())
                .penalizesBy(0);
    }

    @Test
    void majeurNEstPasConcerneParLePlafondHebdomadaireMineur() {
        Animateur majeur = referentMajeur("A1");
        verify("dureeHebdomadaireMaxMineur")
                .given(poste(standStrat, longDay("J1-LONG", 1, D1), majeur),
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
                .given(poste(standStrat, creneauMatin, mineur),
                        poste(standStrat, apremJ2, mineur),
                        parametres)
                .penalizesBy(0);
    }

    // --- Art. L3164-6: public holidays -------------------------------------

    @Test
    void mineurTravaillantLeQuatorzeJuilletEstPenalise() {
        // B8: the bundled scenarios run in July 2026 and cover 14 July
        // (art. L3164-6, list of art. L3133-1).
        Creneau quatorzeJuillet = creneau("FETE-NAT", 7, java.time.LocalDate.of(2026, 7, 14),
                LocalTime.of(11, 0), LocalTime.of(15, 0));
        verify("travailInterditJourFerieMineur")
                .given(poste(standStrat, quatorzeJuillet, mineurDebutant("M1")))
                .penalizesBy(1);
    }

    @Test
    void majeurTravaillantLeQuatorzeJuilletNEstPasPenalise() {
        Creneau quatorzeJuillet = creneau("FETE-NAT", 7, java.time.LocalDate.of(2026, 7, 14),
                LocalTime.of(11, 0), LocalTime.of(15, 0));
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
        return creneau("W29-J" + offsetDepuisLundi, 6 + offsetDepuisLundi, lundi.plusDays(offsetDepuisLundi),
                LocalTime.of(11, 0), LocalTime.of(15, 0));
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
        // Seven days from 11:00 to 15:00: the longest rest is 20 h (15:00 → 11:00
        // the next day), that is 900 min under the 35 h minimum.
        Animateur majeur = referentMajeur("A1");
        Object[] postes = new Object[7];
        for (int i = 0; i < 7; i++) {
            postes[i] = poste(standStrat, jourSemaine29(i), majeur);
        }
        verify("reposHebdomadaireMinimal").given(postes).penalizesBy(35 * 60 - 20 * 60);
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
                .given(poste(standStrat, jourSemaine29(0), mineur),
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
                .given(poste(standStrat, jourSemaine29(0), mineur),
                        poste(standStrat, jourSemaine29(1), mineur),
                        poste(standStrat, jourSemaine29(2), mineur),
                        poste(standStrat, jourSemaine29(3), mineur),
                        poste(standStrat, jourSemaine29(4), mineur))
                .penalizesBy(0);
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
                .given(poste(standStrat, creneauMatin, a1),
                        poste(standStrat, apremJ2, a2),
                        parametres)
                .penalizesBy(0);
    }

    @Test
    void pauseEntreDeuxVacationsLeMemeJourTropCourteEstPenalisee() {
        // creneauMatin ends at 13:00; this shift starts at 13:15, that is only
        // 15 min of break — under the default floor of 30 min.
        Animateur majeur = referentMajeur("A1");
        Creneau vacationProche = creneau("J1-PROCHE", 1, D1, java.time.LocalTime.of(13, 15), java.time.LocalTime.of(17, 15));
        verify("pauseMinimaleEntreVacations")
                .given(poste(standStrat, creneauMatin, majeur),
                        poste(standStrat, vacationProche, majeur),
                        new ParametresLegaux())
                .penalizesBy(15);
    }

    @Test
    void pauseEntreDeuxVacationsLeMemeJourSuffisanteNEstPasPenalisee() {
        Animateur majeur = referentMajeur("A1");
        Creneau vacationEloignee = creneau("J1-LOIN", 1, D1, java.time.LocalTime.of(13, 30), java.time.LocalTime.of(17, 30));
        verify("pauseMinimaleEntreVacations")
                .given(poste(standStrat, creneauMatin, majeur),
                        poste(standStrat, vacationEloignee, majeur),
                        new ParametresLegaux())
                .penalizesBy(0);
    }

}
