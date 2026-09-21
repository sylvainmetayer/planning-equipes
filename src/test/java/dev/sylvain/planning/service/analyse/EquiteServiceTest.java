package dev.sylvain.planning.service.analyse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Emplacement;
import dev.sylvain.planning.domain.NiveauCompetence;
import dev.sylvain.planning.domain.NiveauEffort;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.analyse.EquiteService.LigneEquite;
import dev.sylvain.planning.service.analyse.EquiteService.RapportEquite;
import dev.sylvain.planning.service.analyse.EquiteService.SyntheseColonne;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The equity table read from a plan alone: plain JUnit, no container, no
 * solve. Each column is pinned on the smallest plan that exercises its rule.
 */
class EquiteServiceTest {

    /** Tuesday 14 July 2026: a public holiday on a weekday, so the two columns stay apart. */
    private static final LocalDate FERIE_MARDI = LocalDate.of(2026, 7, 14);

    private static final LocalDate MERCREDI = LocalDate.of(2026, 7, 15);
    private static final LocalDate JEUDI = LocalDate.of(2026, 7, 16);
    private static final LocalDate VENDREDI = LocalDate.of(2026, 7, 17);
    private static final LocalDate SAMEDI = LocalDate.of(2026, 7, 18);

    private static final ParametresLegaux SOIREE_20H = new ParametresLegaux();

    private static long nextCreneauId = 1;
    private static int nextPosteId = 1;

    private static Creneau creneau(LocalDate date, int debutHeure, int finHeure) {
        return new Creneau(
                nextCreneauId++,
                date.getDayOfMonth(),
                date,
                LocalTime.of(debutHeure, 0),
                LocalTime.of(finHeure % 24, 0));
    }

    private static PosteAffectation poste(Stand stand, Creneau creneau, Animateur animateur) {
        PosteAffectation poste = new PosteAffectation("P" + nextPosteId++, stand, creneau);
        poste.setAnimateur(animateur);
        return poste;
    }

    private static Animateur animateur(String id, String prenom, String nom) {
        return new Animateur(id, prenom, nom, LocalDate.of(1990, 1, 1), false);
    }

    private static Stand stand(String id, String... typologies) {
        return new Stand(id, "Stand " + id, Set.of(typologies), 1, 2, false);
    }

    private static RapportEquite compute(List<Animateur> animateurs, List<PosteAffectation> postes) {
        return EquiteService.compute(new PlanningEvenement(MERCREDI, animateurs, postes), SOIREE_20H, Set.of());
    }

    private static LigneEquite ligne(RapportEquite rapport, String animateurId) {
        return rapport.lignes().stream()
                .filter(ligne -> ligne.animateurId().equals(animateurId))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void oneLinePerAssignedAnimateurWithHoursTotalAndPerWeek() {
        Animateur ada = animateur("A-ADA", "Ada", "Lovelace");
        Animateur bob = animateur("A-BOB", "Bob", "Martin");
        Animateur libre = animateur("A-LIBRE", "Nul", "Part");
        Stand stand = stand("S1", "echecs");
        RapportEquite rapport = compute(
                List.of(ada, bob, libre),
                List.of(
                        poste(stand, creneau(MERCREDI, 9, 13), ada),
                        poste(stand, creneau(JEUDI, 14, 18), ada),
                        poste(stand, creneau(MERCREDI, 9, 12), bob),
                        poste(stand, creneau(JEUDI, 9, 12), null)));

        assertThat(rapport.semaines()).containsExactly("2026-W29");
        assertThat(rapport.lignes()).extracting(LigneEquite::animateurId).containsExactly("A-ADA", "A-BOB");
        LigneEquite ligneAda = ligne(rapport, "A-ADA");
        assertThat(ligneAda.nom()).isEqualTo("Ada Lovelace");
        assertThat(ligneAda.heuresTotal()).isCloseTo(8.0, within(0.01));
        assertThat(ligneAda.heuresParSemaine()).containsEntry("2026-W29", 8.0);
        assertThat(ligneAda.postes()).isEqualTo(2);
        assertThat(ligne(rapport, "A-BOB").heuresTotal()).isCloseTo(3.0, within(0.01));
        assertThat(rapport.heureDebutSoiree()).isEqualTo(LocalTime.of(20, 0));
    }

    @Test
    void eveningHoursStartAtTheParameterAndRunToTheEndPastMidnight() {
        Animateur ada = animateur("A-ADA", "Ada", "Lovelace");
        Stand stand = stand("S1");
        ParametresLegaux soiree22h = new ParametresLegaux();
        soiree22h.setHeureDebutSoiree(LocalTime.of(22, 0));
        // 18:00-23:00 straddles the evening; 20:00-01:00 crosses midnight;
        // 09:00-12:00 never reaches it.
        List<PosteAffectation> postes = List.of(
                poste(stand, creneau(MERCREDI, 18, 23), ada),
                poste(stand, creneau(JEUDI, 20, 25), ada),
                poste(stand, creneau(VENDREDI, 9, 12), ada));
        PlanningEvenement planning = new PlanningEvenement(MERCREDI, List.of(ada), postes);

        LigneEquite sous20h =
                EquiteService.compute(planning, SOIREE_20H, Set.of()).lignes().get(0);
        LigneEquite sous22h =
                EquiteService.compute(planning, soiree22h, Set.of()).lignes().get(0);

        assertThat(sous20h.heuresSoiree()).isCloseTo(3.0 + 5.0, within(0.01));
        assertThat(sous22h.heuresSoiree()).isCloseTo(1.0 + 3.0, within(0.01));
        assertThat(sous20h.heuresTotal()).isCloseTo(13.0, within(0.01));
    }

    /**
     * The generator cuts an 18:00→04:00 stretch into three, the last one dated
     * on the next day: whoever holds 02:00-04:00 held the heart of the night.
     * Counted from the evening alone, that seat scored zero and the column
     * measured where the grid had been cut rather than who worked at night.
     */
    @Test
    void aSeatStartingAfterMidnightStillCountsAsEveningHours() {
        Animateur ada = animateur("A-ADA", "Ada", "Lovelace");
        Stand stand = stand("S1");
        RapportEquite rapport = compute(
                List.of(ada),
                List.of(
                        // 01:00-03:00, wholly inside the early-morning window.
                        poste(stand, creneau(JEUDI, 1, 3), ada),
                        // 04:00-08:00: two hours before 06:00, two after.
                        poste(stand, creneau(VENDREDI, 4, 8), ada)));

        LigneEquite ligne = rapport.lignes().get(0);
        assertThat(ligne.heuresTotal()).isCloseTo(6.0, within(0.01));
        assertThat(ligne.heuresSoiree()).isCloseTo(2.0 + 2.0, within(0.01));
    }

    /**
     * The streak is a run of calendar days, which is what the solver's rule
     * counts. Read as positions in the list of event days, it ran straight
     * through a gap — two days apart became a streak of two.
     */
    @Test
    void theLongestStreakDoesNotRunThroughAGapInTheEventDays() {
        Animateur ada = animateur("A-ADA", "Ada", "Lovelace");
        Stand stand = stand("S1");
        // Event on 15-16 July and 20-21 July; Ada works the 16th and the 20th.
        RapportEquite rapport = compute(
                List.of(ada),
                List.of(
                        poste(stand, creneau(JEUDI, 9, 12), ada),
                        poste(stand, creneau(LocalDate.of(2026, 7, 20), 9, 12), ada),
                        poste(stand, creneau(MERCREDI, 9, 12), null),
                        poste(stand, creneau(LocalDate.of(2026, 7, 21), 9, 12), null)));

        assertThat(ligne(rapport, "A-ADA").plusLongueSerie()).isEqualTo(1);
    }

    @Test
    void weekEndAndPublicHolidayCountTheWholePosteOnTheDateOfItsCreneau() {
        Animateur ada = animateur("A-ADA", "Ada", "Lovelace");
        Stand stand = stand("S1");
        RapportEquite rapport = compute(
                List.of(ada),
                List.of(
                        poste(stand, creneau(FERIE_MARDI, 10, 14), ada),
                        poste(stand, creneau(SAMEDI, 10, 13), ada),
                        poste(stand, creneau(MERCREDI, 10, 12), ada)));

        LigneEquite ligne = rapport.lignes().get(0);
        assertThat(ligne.heuresJourFerie()).isCloseTo(4.0, within(0.01));
        assertThat(ligne.heuresWeekEnd()).isCloseTo(3.0, within(0.01));
    }

    @Test
    void demandingSeatsAreExhaustingOrPremiumStands() {
        Animateur ada = animateur("A-ADA", "Ada", "Lovelace");
        Stand normal = stand("S1");
        Stand epuisant = stand("S2");
        epuisant.setNiveauEffort(NiveauEffort.EPUISANT);
        Stand premium = new Stand("S3", "Premium", Set.of(), 1, 1, false, true);
        RapportEquite rapport = compute(
                List.of(ada),
                List.of(
                        poste(normal, creneau(MERCREDI, 9, 12), ada),
                        poste(epuisant, creneau(JEUDI, 9, 12), ada),
                        poste(premium, creneau(VENDREDI, 9, 12), ada)));

        LigneEquite ligne = rapport.lignes().get(0);
        assertThat(ligne.postesPenibles()).isEqualTo(2);
        assertThat(ligne.standsDistincts()).isEqualTo(3);
    }

    @Test
    void distinctTypologiesAreTheOnesTheAnimateurIsAppreciatedForAndAllOfThemForANinja() {
        Animateur ada = animateur("A-ADA", "Ada", "Lovelace");
        ada.setCompetences(Map.of("echecs", NiveauCompetence.REFERENT));
        Animateur ninja = animateur("A-NINJA", "Nina", "Ninja");
        ninja.setNinja(true);
        Stand stand = stand("S1", "echecs", "dames", "go");
        RapportEquite rapport = compute(
                List.of(ada, ninja),
                List.of(poste(stand, creneau(MERCREDI, 9, 12), ada), poste(stand, creneau(MERCREDI, 9, 12), ninja)));

        assertThat(ligne(rapport, "A-ADA").typologiesDistinctes()).isEqualTo(1);
        assertThat(ligne(rapport, "A-NINJA").typologiesDistinctes()).isEqualTo(3);
    }

    @Test
    void locationsPerDayKeepTheBusiestDayAndIgnoreSeatsWithoutALocation() {
        Animateur ada = animateur("A-ADA", "Ada", "Lovelace");
        Stand kiosque = stand("S1");
        kiosque.setEmplacement(new Emplacement("E1", "Kiosque", null, null));
        Stand mairie = stand("S2");
        mairie.setEmplacement(new Emplacement("E2", "Mairie", null, null));
        Stand sansLieu = stand("S3");
        RapportEquite rapport = compute(
                List.of(ada),
                List.of(
                        poste(kiosque, creneau(MERCREDI, 9, 12), ada),
                        poste(mairie, creneau(MERCREDI, 14, 17), ada),
                        poste(sansLieu, creneau(MERCREDI, 18, 19), ada),
                        poste(kiosque, creneau(JEUDI, 9, 12), ada)));

        assertThat(rapport.lignes().get(0).emplacementsDistinctsParJourMax()).isEqualTo(2);
    }

    @Test
    void wishAndAppreciationRatesAreSeatsOverSeats() {
        Animateur ada = animateur("A-ADA", "Ada", "Lovelace");
        ada.setCompetences(Map.of("echecs", NiveauCompetence.DEBUTANT));
        ada.setSouhaits(Set.of("go"));
        Stand echecs = stand("S1", "echecs");
        Stand go = stand("S2", "go");
        Stand dames = stand("S3", "dames");
        RapportEquite rapport = compute(
                List.of(ada),
                List.of(
                        poste(echecs, creneau(MERCREDI, 9, 12), ada),
                        poste(go, creneau(JEUDI, 9, 12), ada),
                        poste(dames, creneau(VENDREDI, 9, 12), ada),
                        poste(echecs, creneau(SAMEDI, 9, 12), ada)));

        LigneEquite ligne = rapport.lignes().get(0);
        assertThat(ligne.tauxSouhaits()).isCloseTo(0.25, within(0.001));
        assertThat(ligne.tauxAppreciation()).isCloseTo(0.5, within(0.001));
    }

    @Test
    void workedDaysRestDaysAndLongestRunFollowTheReposScreen() {
        Animateur ada = animateur("A-ADA", "Ada", "Lovelace");
        ada.setJoursIndisponibles(Set.of(SAMEDI));
        Animateur bob = animateur("A-BOB", "Bob", "Martin");
        Stand stand = stand("S1");
        // Five event days (Tue to Sat); Ada works Tue, Wed, Fri, declared Sat off.
        RapportEquite rapport = compute(
                List.of(ada, bob),
                List.of(
                        poste(stand, creneau(FERIE_MARDI, 9, 12), ada),
                        poste(stand, creneau(MERCREDI, 9, 12), ada),
                        poste(stand, creneau(MERCREDI, 14, 17), ada),
                        poste(stand, creneau(VENDREDI, 9, 12), ada),
                        poste(stand, creneau(JEUDI, 9, 12), bob),
                        poste(stand, creneau(SAMEDI, 9, 12), bob)));

        LigneEquite ligne = ligne(rapport, "A-ADA");
        assertThat(ligne.joursTravailles()).isEqualTo(3);
        assertThat(ligne.joursRepos())
                .as("Thursday only: Saturday was declared off")
                .isEqualTo(1);
        assertThat(ligne.plusLongueSerie()).isEqualTo(2);
    }

    @Test
    void anEmptyPlanGivesNoLineAndNoSynthesis() {
        RapportEquite rapport = EquiteService.compute(
                new PlanningEvenement(null, List.of(animateur("A-ADA", "Ada", "Lovelace")), List.of()),
                SOIREE_20H,
                Set.of());

        assertThat(rapport.lignes()).isEmpty();
        assertThat(rapport.semaines()).isEmpty();
        assertThat(rapport.syntheses()).isEmpty();
        assertThat(rapport.colonnesSolveur()).isNotEmpty();
        assertThat(EquiteService.compute(null, null, null).lignes()).isEmpty();
    }

    /**
     * Hours shared out here are travail effectif, like the caps, the Heures
     * screen and the KPI (ADR 0048): a nine-hour day owes one break past the
     * sixth hour, so it counts 8 h 30. Sharing out amplitude credited somebody
     * with half an hour they spent resting, and made the Équité screen and the
     * Heures screen disagree on one person's total.
     */
    @Test
    void lesHeuresPartageesSontDuTravailEffectif() {
        Stand stand = stand("S1");
        Animateur longue = animateur("A-LONGUE", "P1", "N1");
        Animateur courte = animateur("A-COURTE", "P2", "N2");
        List<PosteAffectation> postes =
                List.of(poste(stand, creneau(MERCREDI, 8, 17), longue), poste(stand, creneau(MERCREDI, 8, 13), courte));

        RapportEquite rapport = compute(List.of(longue, courte), postes);

        assertThat(ligne(rapport, "A-LONGUE").heuresTotal()).isCloseTo(8.5, within(0.001));
        assertThat(ligne(rapport, "A-COURTE").heuresTotal()).isCloseTo(5.0, within(0.001));
        assertThat(ligne(rapport, "A-LONGUE").heuresParSemaine().values())
                .singleElement(org.assertj.core.api.InstanceOfAssertFactories.DOUBLE)
                .isCloseTo(8.5, within(0.001));
    }

    @Test
    void synthesisGivesMedianMinMaxAndStandardDeviationPerColumn() {
        Stand stand = stand("S1");
        List<Animateur> animateurs = new ArrayList<>();
        List<PosteAffectation> postes = new ArrayList<>();
        // Hours per animateur: 1, 2, 4, 6 — an even count, so the median is
        // the mean of the two middle values. None of them reaches past the
        // sixth hour, so no break is owed and the figures below are the
        // statistics alone; the deduction has a test of its own.
        int[] heures = {1, 2, 4, 6};
        for (int i = 0; i < heures.length; i++) {
            Animateur animateur = animateur("A-" + i, "P" + i, "N" + i);
            animateurs.add(animateur);
            postes.add(poste(stand, creneau(MERCREDI, 8, 8 + heures[i]), animateur));
        }

        RapportEquite rapport = compute(animateurs, postes);

        SyntheseColonne total = rapport.syntheses().get("heuresTotal");
        assertThat(total.mediane()).isCloseTo(3.0, within(0.001));
        assertThat(total.min()).isCloseTo(1.0, within(0.001));
        assertThat(total.max()).isCloseTo(6.0, within(0.001));
        assertThat(total.ecartType()).isCloseTo(1.920, within(0.001));
        assertThat(rapport.syntheses().get("2026-W29").mediane()).isCloseTo(3.0, within(0.001));
        assertThat(rapport.syntheses().get("postes").mediane()).isEqualTo(1.0);
        assertThat(rapport.syntheses()).containsKeys("heuresSoiree", "joursRepos", "plusLongueSerie");
    }

    @Test
    void medianOfAnOddCountIsTheMiddleValue() {
        assertThat(EquiteService.median(new double[] {9, 1, 4})).isEqualTo(4.0);
        assertThat(EquiteService.median(new double[] {})).isEqualTo(0.0);
        assertThat(EquiteService.standardDeviation(new double[] {2, 2, 2})).isEqualTo(0.0);
    }

    @Test
    void solverColumnsNameTheirRuleAndWhetherItIsActive() {
        RapportEquite rapport = EquiteService.compute(
                new PlanningEvenement(null, List.of(), List.of()), SOIREE_20H, Set.of("souhaitsIncompatibles"));

        assertThat(rapport.colonnesSolveur())
                .extracting(colonne -> colonne.colonne() + ":" + colonne.contrainte() + ":" + colonne.active())
                .containsExactly(
                        "postes:equilibrerCharge:true",
                        "postesPenibles:equilibrerCreneauxPenibles:true",
                        "typologiesDistinctes:limiterTypologiesDistinctesParAnimateur:true",
                        "emplacementsDistinctsParJourMax:limiterEmplacementsParJour:true",
                        "tauxSouhaits:souhaitsIncompatibles:false",
                        "tauxAppreciation:appreciationIncompatible:true",
                        "plusLongueSerie:maxJoursConsecutifsTravailles:true");
    }

    @Test
    void csvHasOneLinePerAnimateurWithDecimalCommas() {
        Animateur ada = animateur("A-ADA", "Ada", "Lovelace");
        Stand stand = stand("S1", "echecs");
        RapportEquite rapport = compute(List.of(ada), List.of(poste(stand, creneau(SAMEDI, 18, 22), ada)));

        String csv = EquiteService.generateCsv(rapport);

        assertThat(csv)
                .startsWith("animateur;heuresTotal;2026-W29;heuresSoiree;heuresWeekEnd;heuresJourFerie;postes;"
                        + "postesPenibles;standsDistincts;typologiesDistinctes;emplacementsDistinctsParJourMax;"
                        + "tauxSouhaits;tauxAppreciation;joursTravailles;joursRepos;plusLongueSerie\n")
                .endsWith("Ada Lovelace;4,00;4,00;2,00;4,00;0,00;1;0;1;0;0;0,00;0,00;1;0;1\n");
    }
}
