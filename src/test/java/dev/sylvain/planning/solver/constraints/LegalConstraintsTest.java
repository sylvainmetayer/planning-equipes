package dev.sylvain.planning.solver.constraints;

import java.time.LocalTime;

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

    // --- Art. L3163-1 : fenêtre de nuit selon la tranche d'âge -------------

    @Test
    void mineurDeMoinsDe16AnsEstPenaliseDes20Heures() {
        // 20 h-22 h : nuit pour un moins de 16 ans (art. L3163-1).
        Creneau soiree = creneau("J1-SOIREE", 1, D1, LocalTime.of(20, 0), LocalTime.of(22, 0));
        verify("travailDeNuitInterditPourMineur")
                .given(poste(standStrat, soiree, mineurMoinsDe16Debutant("M15")))
                .penalizesBy(1);
    }

    @Test
    void mineurDe16A18AnsNEstPasPenaliseEntre20HEt22H() {
        // Même créneau, 16-18 ans : la nuit ne commence qu'à 22 h (art. L3163-1).
        // Avant le correctif, la fenêtre 20 h était appliquée à tous les mineurs.
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
    void mineurDeMoinsDe16AnsDepassantSeptHeuresParJourEstPenalise() {
        // Art. D4153-3 : 7 h/jour sous 16 ans. Un créneau de 9 h dépasse de 120 min,
        // là où un 16-18 ans ne dépasserait que de 60 min (plafond de 8 h, L3162-1).
        Creneau journee = journeeLongue("J1-LONG", 1, D1);
        verify("dureeQuotidienneMaxMineur")
                .given(poste(standStrat, journee, mineurMoinsDe16Debutant("M15")))
                .penalizesBy(120);
    }

    @Test
    void mineurDeMoinsDe16AnsSousSeptHeuresParJourNEstPasPenalise() {
        Creneau sixHeures = creneau("J1-6H", 1, D1, LocalTime.of(9, 0), LocalTime.of(15, 0));
        verify("dureeQuotidienneMaxMineur")
                .given(poste(standStrat, sixHeures, mineurMoinsDe16Debutant("M15")))
                .penalizesBy(0);
    }

    @Test
    void majeurNEstPasConcerneParLePlafondQuotidien() {
        Creneau journee = journeeLongue("J1-LONG", 1, D1);
        verify("dureeQuotidienneMaxMineur")
                .given(poste(standStrat, journee, majeurReferent("A1")))
                .penalizesBy(0);
    }

    // --- Art. L3131-1 / L3164-1 : repos quotidien minimal ------------------

    @Test
    void majeurAvecMoinsDeOnzeHeuresDeReposEstPenalise() {
        // B1 de l'audit, cas exact de scenario-complet.yaml : 20 h → 00 h puis
        // 08 h → 12 h le lendemain, soit 8 h de repos au lieu de 11 h (L3131-1).
        Animateur majeur = majeurReferent("A1");
        Creneau matin8J2 = creneau("J2-8-12", 2, D2, LocalTime.of(8, 0), LocalTime.of(12, 0));
        verify("reposQuotidienMinimal")
                .given(poste(standStrat, creneauNuit, majeur),
                        poste(standStrat, matin8J2, majeur))
                .penalizesBy(3 * 60);
    }

    @Test
    void majeurAvecOnzeHeuresDeReposNEstPasPenalise() {
        // 20 h → 00 h puis 14 h → 18 h le lendemain : 14 h de repos.
        Animateur majeur = majeurReferent("A1");
        verify("reposQuotidienMinimal")
                .given(poste(standStrat, creneauNuit, majeur),
                        poste(standStrat, apremJ2, majeur))
                .penalizesBy(0);
    }

    @Test
    void mineurDe16A18AnsExigeDouzeHeuresDeRepos() {
        // 09 h → 13 h puis 00 h 30 → ... : ici 18 h → 22 h puis 09 h → 13 h,
        // soit 11 h de repos : conforme pour un majeur, 60 min trop court pour
        // un mineur (12 h, art. L3164-1).
        Animateur mineur = mineurDebutant("M17");
        Creneau soirJ1 = creneau("J1-18-22", 1, D1, LocalTime.of(18, 0), LocalTime.of(22, 0));
        verify("reposQuotidienMinimal")
                .given(poste(standStrat, soirJ1, mineur),
                        poste(standStrat, matinJ2, mineur))
                .penalizesBy(60);
    }

    @Test
    void mineurDeMoinsDe16AnsExigeQuatorzeHeuresDeRepos() {
        // Même paire de créneaux, moins de 16 ans : 14 h exigées, 11 h obtenues.
        Animateur mineur = mineurMoinsDe16Debutant("M15");
        Creneau soirJ1 = creneau("J1-18-22", 1, D1, LocalTime.of(18, 0), LocalTime.of(22, 0));
        verify("reposQuotidienMinimal")
                .given(poste(standStrat, soirJ1, mineur),
                        poste(standStrat, matinJ2, mineur))
                .penalizesBy(3 * 60);
    }

    @Test
    void deuxCreneauxDuMemeJourNeDeclenchentPasLeReposQuotidien() {
        Animateur majeur = majeurReferent("A1");
        verify("reposQuotidienMinimal")
                .given(poste(standStrat, creneauMatin, majeur),
                        poste(standStrat, apresMidi("J1-AM", 1, D1), majeur))
                .penalizesBy(0);
    }

    // --- Art. L3121-18 : 10 h/jour pour un majeur --------------------------

    @Test
    void majeurDepassantDixHeuresParJourEstPenalise() {
        // B2 de l'audit : les trois créneaux d'une journée de scenario-complet
        // totalisent 14 h (4 + 6 + 4), soit 240 min au-dessus du plafond.
        Animateur majeur = majeurReferent("A1");
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
        Animateur majeur = majeurReferent("A1");
        Creneau matin = creneau("J1-8-12", 1, D1, LocalTime.of(8, 0), LocalTime.of(12, 0));
        Creneau aprem = creneau("J1-14-20", 1, D1, LocalTime.of(14, 0), LocalTime.of(20, 0));
        verify("dureeQuotidienneMaxMajeur")
                .given(poste(standStrat, matin, majeur),
                        poste(standStrat, aprem, majeur))
                .penalizesBy(0);
    }

    @Test
    void mineurNEstPasConcerneParLePlafondQuotidienMajeur() {
        Animateur mineur = mineurDebutant("M1");
        verify("dureeQuotidienneMaxMajeur")
                .given(poste(standStrat, journeeLongue("J1-LONG", 1, D1), mineur))
                .penalizesBy(0);
    }

    // --- Art. L3121-16 : 6 h de travail continu / pause de 20 min ----------

    @Test
    void majeurEnchainantDeuxCreneauxSansPauseSuffisanteEstPenalise() {
        // B6 : 14 h → 20 h puis 20 h → 00 h, aucune interruption : 10 h d'un
        // seul tenant, soit 240 min au-dessus du maximum de 6 h.
        Animateur majeur = majeurReferent("A1");
        Creneau aprem = creneau("J1-14-20", 1, D1, LocalTime.of(14, 0), LocalTime.of(20, 0));
        Creneau soiree = creneau("J1-20-00", 1, D1, LocalTime.of(20, 0), LocalTime.of(0, 0));
        verify("travailContinuMaxMajeur")
                .given(poste(standStrat, aprem, majeur),
                        poste(standStrat, soiree, majeur))
                .penalizesBy(4 * 60);
    }

    @Test
    void unePauseDeVingtMinutesCoupeLaSequenceDuMajeur() {
        // Même journée, mais 20 minutes d'interruption : deux séquences de
        // 5 h 40 et 4 h, toutes deux sous le maximum de 6 h.
        Animateur majeur = majeurReferent("A1");
        Creneau aprem = creneau("J1-14-1940", 1, D1, LocalTime.of(14, 0), LocalTime.of(19, 40));
        Creneau soiree = creneau("J1-20-00", 1, D1, LocalTime.of(20, 0), LocalTime.of(0, 0));
        verify("travailContinuMaxMajeur")
                .given(poste(standStrat, aprem, majeur),
                        poste(standStrat, soiree, majeur))
                .penalizesBy(0);
    }

    @Test
    void unePauseTropCourteNeCoupePasLaSequenceDuMajeur() {
        // 10 minutes d'interruption : la loi exige 20 minutes consécutives, la
        // séquence reste donc continue (6 h 10 mesurées bord à bord).
        Animateur majeur = majeurReferent("A1");
        Creneau debut = creneau("J1-14-17", 1, D1, LocalTime.of(14, 0), LocalTime.of(17, 0));
        Creneau suite = creneau("J1-1710-2010", 1, D1, LocalTime.of(17, 10), LocalTime.of(20, 10));
        verify("travailContinuMaxMajeur")
                .given(poste(standStrat, debut, majeur),
                        poste(standStrat, suite, majeur))
                .penalizesBy(10);
    }

    // --- Art. L3162-3 : 4 h 30 de travail continu / pause de 30 min --------

    @Test
    void mineurSurUnCreneauDeSixHeuresEstPenalise() {
        // B7 : le créneau 14 h → 20 h de scenario-complet.yaml, tenu par un
        // mineur, dépasse de 1 h 30 la durée maximale de travail continu.
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
        // 20 minutes suffisent à un majeur, pas à un mineur (30 min, L3162-3) :
        // la séquence court de 9 h à 16 h 20, soit 7 h 20, donc 170 min de trop.
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
        Animateur majeur = majeurReferent("A1");
        Creneau aprem = creneau("J1-14-20", 1, D1, LocalTime.of(14, 0), LocalTime.of(20, 0));
        verify("travailContinuMaxMineur")
                .given(poste(standStrat, aprem, majeur))
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

    // --- Art. L3164-6 : jours fériés --------------------------------------

    @Test
    void mineurTravaillantLeQuatorzeJuilletEstPenalise() {
        // B8 : les scénarios livrés courent en juillet 2026 et couvrent le
        // 14 juillet (art. L3164-6, liste de l'art. L3133-1).
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
                .given(poste(standStrat, quatorzeJuillet, majeurReferent("A1")))
                .penalizesBy(0);
    }

    @Test
    void mineurTravaillantUnJourOrdinaireNEstPasPenalise() {
        verify("travailInterditJourFerieMineur")
                .given(poste(standStrat, creneauMatin, mineurDebutant("M1")))
                .penalizesBy(0);
    }

    // --- Art. L3132-1 / L3132-2 / L3164-2 : repos hebdomadaire -------------

    /** Créneau court (11 h → 15 h) du jour J de la semaine ISO 2026-W29. */
    private Creneau jourSemaine29(int offsetDepuisLundi) {
        java.time.LocalDate lundi = java.time.LocalDate.of(2026, 7, 13);
        return creneau("W29-J" + offsetDepuisLundi, 6 + offsetDepuisLundi, lundi.plusDays(offsetDepuisLundi),
                LocalTime.of(11, 0), LocalTime.of(15, 0));
    }

    @Test
    void septJoursTravaillesDansLaSemaineEstPenalise() {
        // B4 : le festival dure 15 jours ; rien n'empêchait d'affecter un
        // animateur sept jours d'affilée (art. L3132-1).
        Animateur majeur = majeurReferent("A1");
        Object[] postes = new Object[7];
        for (int i = 0; i < 7; i++) {
            postes[i] = poste(standStrat, jourSemaine29(i), majeur);
        }
        verify("maxJoursTravaillesParSemaine").given(postes).penalizesBy(1);
    }

    @Test
    void sixJoursTravaillesDansLaSemaineNEstPasPenalise() {
        Animateur majeur = majeurReferent("A1");
        Object[] postes = new Object[6];
        for (int i = 0; i < 6; i++) {
            postes[i] = poste(standStrat, jourSemaine29(i), majeur);
        }
        verify("maxJoursTravaillesParSemaine").given(postes).penalizesBy(0);
    }

    @Test
    void semaineSansTrenteCinqHeuresDeReposConsecutivesEstPenalisee() {
        // Sept jours de 11 h à 15 h : le plus long repos est de 20 h
        // (15 h → 11 h le lendemain), soit 900 min sous le minimum de 35 h.
        Animateur majeur = majeurReferent("A1");
        Object[] postes = new Object[7];
        for (int i = 0; i < 7; i++) {
            postes[i] = poste(standStrat, jourSemaine29(i), majeur);
        }
        verify("reposHebdomadaireMinimal").given(postes).penalizesBy(35 * 60 - 20 * 60);
    }

    @Test
    void semaineAvecUnJourEtDemiDeReposNEstPasPenalisee() {
        // Travail lundi à vendredi, puis rien : de vendredi 15 h au lundi
        // suivant 00 h, largement plus de 35 h consécutives.
        Animateur majeur = majeurReferent("A1");
        Object[] postes = new Object[5];
        for (int i = 0; i < 5; i++) {
            postes[i] = poste(standStrat, jourSemaine29(i), majeur);
        }
        verify("reposHebdomadaireMinimal").given(postes).penalizesBy(0);
    }

    @Test
    void mineurSansDeuxJoursDeReposConsecutifsEstPenalise() {
        // Travail lundi, mardi, jeudi, vendredi, dimanche : les jours libres
        // (mercredi, samedi) ne sont jamais consécutifs (art. L3164-2).
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
        // Travail lundi à vendredi, samedi et dimanche libres.
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
        Animateur majeur = majeurReferent("A1");
        Object[] postes = new Object[7];
        for (int i = 0; i < 7; i++) {
            postes[i] = poste(standStrat, jourSemaine29(i), majeur);
        }
        verify("reposHebdomadaireMineur").given(postes).penalizesBy(0);
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

    @Test
    void pauseEntreDeuxVacationsLeMemeJourTropCourteEstPenalisee() {
        // creneauMatin finit à 13h ; cette vacation démarre à 13h15, soit
        // seulement 15 min de pause — sous le plafond par défaut de 30 min.
        Animateur majeur = majeurReferent("A1");
        Creneau vacationProche = creneau("J1-PROCHE", 1, D1, java.time.LocalTime.of(13, 15), java.time.LocalTime.of(17, 15));
        verify("pauseMinimaleEntreVacations")
                .given(poste(standStrat, creneauMatin, majeur),
                        poste(standStrat, vacationProche, majeur),
                        new ParametresLegaux())
                .penalizesBy(15);
    }

    @Test
    void pauseEntreDeuxVacationsLeMemeJourSuffisanteNEstPasPenalisee() {
        Animateur majeur = majeurReferent("A1");
        Creneau vacationEloignee = creneau("J1-LOIN", 1, D1, java.time.LocalTime.of(13, 30), java.time.LocalTime.of(17, 30));
        verify("pauseMinimaleEntreVacations")
                .given(poste(standStrat, creneauMatin, majeur),
                        poste(standStrat, vacationEloignee, majeur),
                        new ParametresLegaux())
                .penalizesBy(0);
    }

}
