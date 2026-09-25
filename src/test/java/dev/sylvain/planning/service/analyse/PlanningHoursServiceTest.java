package dev.sylvain.planning.service.analyse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.analyse.PlanningHoursService.HeuresAnimateur;
import dev.sylvain.planning.service.analyse.PlanningHoursService.HeuresRapport;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlanningHoursServiceTest {

    private final PlanningHoursService service = new PlanningHoursService();

    @Test
    void groupsHoursByIsoWeekAndComputesTotal() {
        // 2026-08-14 (Fri) and 2026-08-15 (Sat) fall in ISO week 2026-W33;
        // 2026-08-24 (Mon) falls in the following ISO week 2026-W35.
        Stand stand = new Stand("STAND-1", "Stand", java.util.Set.of(), 1, 2, false);
        Creneau creneauJ1 = new Creneau(1L, 1, LocalDate.of(2026, 8, 14), LocalTime.of(9, 0), LocalTime.of(13, 0));
        Creneau creneauJ2 = new Creneau(2L, 2, LocalDate.of(2026, 8, 15), LocalTime.of(14, 0), LocalTime.of(18, 0));
        Creneau creneauJ3 = new Creneau(3L, 3, LocalDate.of(2026, 8, 24), LocalTime.of(9, 0), LocalTime.of(12, 0));

        Animateur ada = new Animateur("A-ADA", "Ada", "Lovelace", LocalDate.of(1990, 1, 1), false);

        PosteAffectation poste1 = new PosteAffectation("P1", stand, creneauJ1);
        poste1.setAnimateur(ada);
        PosteAffectation poste2 = new PosteAffectation("P2", stand, creneauJ2);
        poste2.setAnimateur(ada);
        PosteAffectation poste3 = new PosteAffectation("P3", stand, creneauJ3);
        poste3.setAnimateur(ada);
        PosteAffectation posteNonAssigne = new PosteAffectation("P4", stand, creneauJ1);

        PlanningEvenement planning = new PlanningEvenement(
                creneauJ1.getDate(), List.of(ada), List.of(poste1, poste2, poste3, posteNonAssigne));

        HeuresRapport rapport = service.compute(planning);

        assertThat(rapport.semaines()).containsExactly("2026-W33", "2026-W35");
        assertThat(rapport.animateurs()).hasSize(1);
        HeuresAnimateur ligne = rapport.animateurs().get(0);
        assertThat(ligne.animateurId()).isEqualTo("A-ADA");
        assertThat(ligne.heuresParSemaine().get("2026-W33")).isCloseTo(8.0, within(0.01));
        assertThat(ligne.heuresParSemaine().get("2026-W35")).isCloseTo(3.0, within(0.01));
        assertThat(ligne.total()).isCloseTo(11.0, within(0.01));
    }

    /**
     * Regression: a poste narrowed by a partial stand closure (issue #60)
     * must count only the time actually staffed, not its créneau's full span
     * — the bug reported live (Oscar Fontaine shown working the whole
     * 13:40-19:00 créneau on a stand closed 14:00-16:00 inside it).
     */
    @Test
    void countsOnlyTheEffectiveWindowWhenAPosteIsNarrowedByAPartialClosure() {
        Stand stand = new Stand("STAND-1", "Stand", java.util.Set.of(), 1, 1, false);
        Creneau creneau = new Creneau(1L, 1, LocalDate.of(2026, 8, 14), LocalTime.of(13, 40), LocalTime.of(19, 0));
        Animateur oscar = new Animateur("A-OSCAR", "Oscar", "Fontaine", LocalDate.of(1990, 1, 1), false);

        PosteAffectation poste = new PosteAffectation("P1", stand, creneau);
        poste.setAnimateur(oscar);
        poste.setHeureDebutEffective(LocalTime.of(16, 0));
        poste.setHeureFinEffective(LocalTime.of(19, 0));

        PlanningEvenement planning = new PlanningEvenement(creneau.getDate(), List.of(oscar), List.of(poste));

        HeuresRapport rapport = service.compute(planning);

        assertThat(rapport.animateurs().get(0).total()).isCloseTo(3.0, within(0.01));
    }

    @Test
    void csvHasOneColumnPerWeekPlusTotal() {
        Stand stand = new Stand("STAND-1", "Stand", java.util.Set.of(), 1, 1, false);
        Creneau creneau = new Creneau(1L, 1, LocalDate.of(2026, 8, 14), LocalTime.of(9, 0), LocalTime.of(13, 0));
        Animateur ada = new Animateur("A-ADA", "Ada", "Lovelace", LocalDate.of(1990, 1, 1), false);
        PosteAffectation poste = new PosteAffectation("P1", stand, creneau);
        poste.setAnimateur(ada);
        PlanningEvenement planning = new PlanningEvenement(creneau.getDate(), List.of(ada), List.of(poste));

        String csv = service.generateCsv(service.compute(planning));

        // Comma, not dot: a French spreadsheet reads « 4.00 » as text, and the
        // column an organiser wants to sum then sums to zero.
        assertThat(csv).isEqualTo("""
                        animateur;2026-W33;total;dimanche;jours feries;dont dimanches feries;apres 22h
                        Ada Lovelace;4,00;4,00;0,00;0,00;0,00;0,00
                        """);
    }

    // --- Payroll counters (issue #597) -------------------------------------

    private static final Stand STAND = new Stand("STAND-RH", "Stand", java.util.Set.of(), 1, 2, false);

    private static PosteAffectation poste(String id, Animateur qui, LocalDate date, int debut, int fin) {
        Creneau creneau = new Creneau((long) id.hashCode(), 1, date, LocalTime.of(debut, 0), LocalTime.of(fin % 24, 0));
        PosteAffectation poste = new PosteAffectation(id, STAND, creneau);
        poste.setAnimateur(qui);
        return poste;
    }

    /**
     * Sunday alone, never « week-end »: the Équité screen adds Saturday in, and
     * only Sunday carries a premium. 2026-08-15 is a Saturday, 2026-08-16 a
     * Sunday.
     */
    @Test
    void leSamediNeComptePasDansLesHeuresDuDimanche() {
        Animateur ada = new Animateur("A-ADA", "Ada", "Lovelace", LocalDate.of(1990, 1, 1), false);
        PosteAffectation samedi = poste("SAM", ada, LocalDate.of(2026, 8, 15), 9, 13);
        PosteAffectation dimanche = poste("DIM", ada, LocalDate.of(2026, 8, 16), 9, 12);

        HeuresAnimateur ligne = service.compute(
                        new PlanningEvenement(LocalDate.of(2026, 8, 15), List.of(ada), List.of(samedi, dimanche)))
                .animateurs()
                .get(0);

        assertThat(ligne.heuresDimanche()).isCloseTo(3.0, within(0.01));
        assertThat(ligne.total()).isCloseTo(7.0, within(0.01));
    }

    /**
     * 2026-08-15 is the Assumption, a public holiday, and a Saturday: the
     * holiday column counts it, the Sunday one does not.
     */
    @Test
    void unJourFerieEstCompteQuelQueSoitLeJourDeLaSemaine() {
        Animateur ada = new Animateur("A-ADA", "Ada", "Lovelace", LocalDate.of(1990, 1, 1), false);
        PosteAffectation ferie = poste("FER", ada, LocalDate.of(2026, 8, 15), 9, 13);

        HeuresAnimateur ligne = service.compute(
                        new PlanningEvenement(LocalDate.of(2026, 8, 15), List.of(ada), List.of(ferie)))
                .animateurs()
                .get(0);

        assertThat(ligne.heuresJourFerie()).isCloseTo(4.0, within(0.01));
        assertThat(ligne.heuresDimanche()).isZero();
        assertThat(ligne.heuresDimancheFerie()).isZero();
    }

    /**
     * 2026-11-01 is a Sunday <b>and</b> All Saints' Day: the hours land in both
     * columns, and their overlap is reported on its own so the screen can say
     * so rather than let a reader add the two up.
     */
    @Test
    void unDimancheFerieEstCompteDansLesDeuxColonnesEtLeDitBien() {
        Animateur ada = new Animateur("A-ADA", "Ada", "Lovelace", LocalDate.of(1990, 1, 1), false);
        LocalDate dimancheFerie = LocalDate.of(2026, 11, 1);
        assertThat(dimancheFerie.getDayOfWeek()).isEqualTo(java.time.DayOfWeek.SUNDAY);
        assertThat(dev.sylvain.planning.domain.JoursFeries.isFerieInFrance(dimancheFerie))
                .isTrue();
        PosteAffectation poste = poste("DF", ada, dimancheFerie, 9, 13);

        HeuresAnimateur ligne = service.compute(new PlanningEvenement(dimancheFerie, List.of(ada), List.of(poste)))
                .animateurs()
                .get(0);

        assertThat(ligne.heuresDimanche()).isCloseTo(4.0, within(0.01));
        assertThat(ligne.heuresJourFerie()).isCloseTo(4.0, within(0.01));
        assertThat(ligne.heuresDimancheFerie()).isCloseTo(4.0, within(0.01));
        assertThat(ligne.total()).isCloseTo(4.0, within(0.01));
    }

    /** Prorated, never the whole seat: a 20:00-23:00 vacation owes one hour. */
    @Test
    void lesHeuresDeNuitSontComptéesAuProrataDepuis22h() {
        Animateur ada = new Animateur("A-ADA", "Ada", "Lovelace", LocalDate.of(1990, 1, 1), false);
        PosteAffectation soiree = poste("SOIR", ada, LocalDate.of(2026, 8, 14), 20, 23);

        HeuresAnimateur ligne = service.compute(
                        new PlanningEvenement(LocalDate.of(2026, 8, 14), List.of(ada), List.of(soiree)))
                .animateurs()
                .get(0);

        assertThat(ligne.heuresNuit()).isCloseTo(1.0, within(0.01));
        assertThat(ligne.total()).isCloseTo(3.0, within(0.01));
    }

    /** A seat crossing midnight is measured past 24:00: 22:00-02:00 is four night hours. */
    @Test
    void uneVacationQuiPasseMinuitCompteToutesSesHeuresDeNuit() {
        Animateur ada = new Animateur("A-ADA", "Ada", "Lovelace", LocalDate.of(1990, 1, 1), false);
        PosteAffectation nuit = poste("NUIT", ada, LocalDate.of(2026, 8, 14), 22, 26);

        HeuresAnimateur ligne = service.compute(
                        new PlanningEvenement(LocalDate.of(2026, 8, 14), List.of(ada), List.of(nuit)))
                .animateurs()
                .get(0);

        assertThat(ligne.heuresNuit()).isCloseTo(4.0, within(0.01));
    }

    /**
     * The night threshold is the law's, not the Équité screen's comfort one:
     * a 20:00-22:00 vacation owes nothing here, where {@code heureDebutSoiree}
     * (20:00 by default) would have counted two hours.
     */
    @Test
    void leSeuilDeNuitEstIndependantDeLHeureDeDebutDeSoiree() {
        Animateur ada = new Animateur("A-ADA", "Ada", "Lovelace", LocalDate.of(1990, 1, 1), false);
        PosteAffectation soiree = poste("TOT", ada, LocalDate.of(2026, 8, 14), 20, 22);

        HeuresAnimateur ligne = service.compute(
                        new PlanningEvenement(LocalDate.of(2026, 8, 14), List.of(ada), List.of(soiree)))
                .animateurs()
                .get(0);

        assertThat(ligne.heuresNuit()).isZero();
    }

    /** The CSV carries the four payroll columns, in the order the screen shows them. */
    @Test
    void leCsvPorteLesColonnesDeLaPaie() {
        Animateur ada = new Animateur("A-ADA", "Ada", "Lovelace", LocalDate.of(1990, 1, 1), false);
        PosteAffectation dimancheSoir = poste("DS", ada, LocalDate.of(2026, 8, 16), 20, 23);

        HeuresRapport rapport =
                service.compute(new PlanningEvenement(LocalDate.of(2026, 8, 16), List.of(ada), List.of(dimancheSoir)));
        String csv = service.generateCsv(rapport);

        assertThat(csv.lines().findFirst().orElseThrow())
                .endsWith(";total;dimanche;jours feries;dont dimanches feries;apres 22h");
        assertThat(csv).contains("Ada Lovelace;3,00;3,00;3,00;0,00;0,00;1,00");
    }
}
