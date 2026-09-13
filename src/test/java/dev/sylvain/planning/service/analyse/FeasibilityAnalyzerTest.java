package dev.sylvain.planning.service.analyse;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.IndisponibiliteStand;
import dev.sylvain.planning.domain.NiveauCompetence;
import dev.sylvain.planning.domain.OuvertureStand;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypeContrainteAdHoc;
import dev.sylvain.planning.service.analyse.FeasibilityAnalyzer.CauseInfaisabilite;
import dev.sylvain.planning.service.analyse.FeasibilityAnalyzer.FeasibilityReport;
import dev.sylvain.planning.service.analyse.FeasibilityAnalyzer.SeveriteInfaisabilite;
import dev.sylvain.planning.service.analyse.FeasibilityAnalyzer.TypeCauseInfaisabilite;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FeasibilityAnalyzerTest {

    private final FeasibilityAnalyzer analyzer = new FeasibilityAnalyzer();

    @Test
    void feasibleWhenEnoughCompetentAvailableAnimateurs() {
        Stand stand = stand("stand-1", 2, "STRATEGIE");
        Creneau creneau = creneau(1, LocalDate.of(2026, 8, 1));
        Animateur a1 = animateur("a1", "STRATEGIE");
        Animateur a2 = animateur("a2", "STRATEGIE");
        Animateur a3 = animateur("a3", "STRATEGIE");

        FeasibilityReport report = analyzer.analyze(List.of(a1, a2, a3), List.of(stand), List.of(creneau));

        assertThat(report.feasible()).isTrue();
        assertThat(report.manqueAnimateurs()).isZero();
        assertThat(report.causes()).isEmpty();
        assertThat(report.totalCauses()).isZero();
    }

    @Test
    void infeasibleWhenNotEnoughCompetentAnimateurs() {
        Stand stand = stand("stand-1", 5, "STRATEGIE");
        Creneau creneau = creneau(1, LocalDate.of(2026, 8, 1));
        Animateur a1 = animateur("a1", "STRATEGIE");
        Animateur a2 = animateur("a2", "STRATEGIE");

        FeasibilityReport report = analyzer.analyze(List.of(a1, a2), List.of(stand), List.of(creneau));

        assertThat(report.feasible()).isFalse();
        assertThat(report.manqueAnimateurs()).isEqualTo(3);
        assertThat(report.message()).contains("3 animateurs");
        assertThat(report.totalCauses()).isEqualTo(1);

        CauseInfaisabilite cause = report.causes().getFirst();
        assertThat(cause.type()).isEqualTo(TypeCauseInfaisabilite.CRENEAU_SOUS_EFFECTIF);
        assertThat(cause.severite()).isEqualTo(SeveriteInfaisabilite.ELEVE);
        assertThat(cause.creneauId()).isEqualTo(1L);
        assertThat(cause.date()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(cause.heureDebut()).isEqualTo(LocalTime.of(10, 0));
        assertThat(cause.heureFin()).isEqualTo(LocalTime.of(12, 0));
        assertThat(cause.standIds()).containsExactly("stand-1");
        assertThat(cause.demande()).isEqualTo(5);
        assertThat(cause.capacite()).isEqualTo(2);
        assertThat(cause.manque()).isEqualTo(3);
        assertThat(cause.message()).contains("2026-08-01 10:00-12:00").contains("il manque 3 animateurs");
    }

    @Test
    void reportsEveryCreneauInShortfallRankedByGravity() {
        Stand stand = stand("stand-1", 3, "STRATEGIE");
        LocalDate samedi = LocalDate.of(2026, 8, 1);
        LocalDate dimanche = LocalDate.of(2026, 8, 2);
        Creneau creneauSamedi = creneau(1, samedi);
        Creneau creneauDimanche = creneau(2, dimanche);

        Animateur a1 = animateur("a1", "STRATEGIE");
        Animateur a2 = animateur("a2", "STRATEGIE");
        a1.setJoursIndisponibles(Set.of(samedi));
        // Saturday: 1 animateur for 3 seats (2 missing); Sunday: 2 animateurs
        // for 3 seats (1 missing). Both timeslots must show up, the most
        // critical one first.

        FeasibilityReport report =
                analyzer.analyze(List.of(a1, a2), List.of(stand), List.of(creneauDimanche, creneauSamedi));

        assertThat(report.feasible()).isFalse();
        assertThat(report.manqueAnimateurs()).isEqualTo(2);
        assertThat(report.totalCauses()).isEqualTo(2);
        assertThat(report.causes()).hasSize(2);
        assertThat(report.causes()).extracting(CauseInfaisabilite::date).containsExactly(samedi, dimanche);
        assertThat(report.causes()).extracting(CauseInfaisabilite::manque).containsExactly(2, 1);
    }

    @Test
    void severiteCritiqueQuandAucunePlaceNePeutEtreCouverte() {
        Stand stand = stand("stand-1", 2, "STRATEGIE");
        LocalDate samedi = LocalDate.of(2026, 8, 1);
        LocalDate dimanche = LocalDate.of(2026, 8, 2);
        Creneau creneauSamedi = creneau(1, samedi);
        Creneau creneauDimanche = creneau(2, dimanche);

        Animateur a1 = animateur("a1", "STRATEGIE");
        a1.setJoursIndisponibles(Set.of(samedi));

        FeasibilityReport report =
                analyzer.analyze(List.of(a1), List.of(stand), List.of(creneauSamedi, creneauDimanche));

        // Saturday: nobody, so missing (2) >= demand (2) -> CRITIQUE, and at the
        // top of the list. Sunday: one animateur for two seats -> ELEVE.
        assertThat(report.causes())
                .extracting(CauseInfaisabilite::severite)
                .containsExactly(SeveriteInfaisabilite.CRITIQUE, SeveriteInfaisabilite.ELEVE);
        assertThat(report.causes().getFirst().date()).isEqualTo(samedi);
        assertThat(report.causes().getFirst().capacite()).isZero();
    }

    @Test
    void standSansAucunAnimateurCompetentNEstPlusUneCause() {
        // A competence is now medium (a liking), not a coverage requirement: an
        // available animateur with no liking for "stand-2" still counts towards
        // the capacity, and the planning stays feasible in the sense of
        // FeasibilityAnalyzer.
        Stand couvert = stand("stand-1", 1, "STRATEGIE");
        Stand orphelin = stand("stand-2", 1, "ADRESSE");
        Creneau creneau = creneau(1, LocalDate.of(2026, 8, 1));
        Animateur a1 = animateur("a1", "STRATEGIE");
        Animateur a2 = animateur("a2", "STRATEGIE");

        FeasibilityReport report = analyzer.analyze(List.of(a1, a2), List.of(couvert, orphelin), List.of(creneau));

        assertThat(report.feasible()).isTrue();
        assertThat(report.totalCauses()).isZero();
    }

    @Test
    void laListeDesCausesEstPlafonneeMaisTotalCausesResteExhaustif() {
        Stand stand = stand("stand-1", 2, "STRATEGIE");
        List<Creneau> creneaux = new java.util.ArrayList<>();
        Set<LocalDate> jours = new java.util.HashSet<>();
        for (int jour = 1; jour <= 14; jour++) {
            LocalDate date = LocalDate.of(2026, 8, jour);
            creneaux.add(creneau(jour, date));
            jours.add(date);
        }
        // Away for the whole event: no day counts towards the capacity.
        Animateur absent = animateur("a1", "STRATEGIE");
        absent.setJoursIndisponibles(jours);

        FeasibilityReport report = analyzer.analyze(List.of(absent), List.of(stand), creneaux);

        assertThat(report.totalCauses()).isEqualTo(14);
        assertThat(report.causes()).hasSize(10);
        assertThat(report.message()).contains("14 causes bloquantes");
    }

    @Test
    void animateurWithoutMatchingCompetenceStillCountsTowardsCapacity() {
        // A competence is a medium liking now, no longer a coverage
        // requirement: an available animateur who is not "competent" for the
        // stand still counts in the raw capacity.
        Stand stand = stand("stand-1", 1, "STRATEGIE");
        Creneau creneau = creneau(1, LocalDate.of(2026, 8, 1));
        Animateur incompetent = animateur("a2", "ADRESSE");

        FeasibilityReport report = analyzer.analyze(List.of(incompetent), List.of(stand), List.of(creneau));

        assertThat(report.feasible()).isTrue();
        assertThat(report.manqueAnimateurs()).isZero();
    }

    @Test
    void standFermeSurUnCreneauNeComptePasDansLaDemande() {
        // The stand requires 3 seats but is closed (an indisponibilite covering
        // the whole timeslot): no seat is generated, hence no demand (see
        // ProblemBuilder.buildPostes). A second stand of one seat stays open, so
        // the edition still has something to staff.
        Stand stand = stand("stand-1", 3, "STRATEGIE");
        Stand ouvert = stand("stand-2", 1, "STRATEGIE");
        Creneau creneau = creneau(1, LocalDate.of(2026, 8, 1));
        stand.setIndisponibilites(List.of(new IndisponibiliteStand(
                null, creneau.getDate(), creneau.getHeureDebut(), creneau.getHeureFin(), null)));

        FeasibilityReport report =
                analyzer.analyze(List.of(animateur("a1", "STRATEGIE")), List.of(stand, ouvert), List.of(creneau));

        assertThat(report.feasible()).isTrue();
        assertThat(report.manqueAnimateurs()).isZero();
        assertThat(report.causes()).isEmpty();
    }

    @Test
    void laDemandeSuitLEffectifDeLaFenetreQuandElleEnNommeUn() {
        // The stand's minimum is 1, but its opening window on that day asks for
        // 4: seat generation creates 4 seats, so 2 animateurs leave 2 missing.
        Stand stand = stand("stand-1", 1, "STRATEGIE");
        Creneau creneau = creneau(1, LocalDate.of(2026, 8, 1));
        stand.setOuvertures(List.of(
                new OuvertureStand(null, creneau.getDate(), LocalTime.of(10, 0), LocalTime.of(12, 0), null, 4)));

        FeasibilityReport report = analyzer.analyze(
                List.of(animateur("a1", "STRATEGIE"), animateur("a2", "STRATEGIE")), List.of(stand), List.of(creneau));

        assertThat(report.feasible()).isFalse();
        CauseInfaisabilite cause = report.causes().getFirst();
        assertThat(cause.demande()).isEqualTo(4);
        assertThat(cause.capacite()).isEqualTo(2);
        assertThat(cause.manque()).isEqualTo(2);
    }

    @Test
    void uneFenetreDemandantMoinsQueLeMinimumDuStandAllegeLaDemande() {
        // Minimum 3 on the stand, but the window of that day names 1: one
        // animateur is enough, where counting effectifMin would announce 2 missing.
        Stand stand = stand("stand-1", 3, "STRATEGIE");
        Creneau creneau = creneau(1, LocalDate.of(2026, 8, 1));
        stand.setOuvertures(List.of(
                new OuvertureStand(null, creneau.getDate(), LocalTime.of(10, 0), LocalTime.of(12, 0), null, 1)));

        FeasibilityReport report =
                analyzer.analyze(List.of(animateur("a1", "STRATEGIE")), List.of(stand), List.of(creneau));

        assertThat(report.feasible()).isTrue();
        assertThat(report.causes()).isEmpty();
    }

    @Test
    void deuxSegmentsSuccessifsDemandentLePlusChargeDesDeuxPasLeurSomme() {
        // 10:00-11:00 needs 2, 11:00-12:00 needs 4: nobody has to hold both at
        // once, so 4 animateurs cover the slot and 3 leave exactly one missing.
        Stand stand = stand("stand-1", 1, "STRATEGIE");
        Creneau creneau = creneau(1, LocalDate.of(2026, 8, 1));
        stand.setOuvertures(List.of(
                new OuvertureStand(null, creneau.getDate(), LocalTime.of(10, 0), LocalTime.of(11, 0), null, 2),
                new OuvertureStand(null, creneau.getDate(), LocalTime.of(11, 0), LocalTime.of(12, 0), null, 4)));
        List<Animateur> quatre = List.of(
                animateur("a1", "STRATEGIE"),
                animateur("a2", "STRATEGIE"),
                animateur("a3", "STRATEGIE"),
                animateur("a4", "STRATEGIE"));

        assertThat(analyzer.analyze(quatre, List.of(stand), List.of(creneau)).feasible())
                .isTrue();

        FeasibilityReport troisSeulement = analyzer.analyze(quatre.subList(0, 3), List.of(stand), List.of(creneau));
        assertThat(troisSeulement.feasible()).isFalse();
        assertThat(troisSeulement.causes().getFirst().demande()).isEqualTo(4);
        assertThat(troisSeulement.causes().getFirst().manque()).isEqualTo(1);
    }

    @Test
    void uneVacationDeCouverturePauseNeDemandeQueLaMoitieDeLaFenetre() {
        // Seat generation halves the headcount on a break-covering shift, so a
        // window of 4 asks for 2 seats: two animateurs are enough.
        Stand stand = stand("stand-1", 1, "STRATEGIE");
        Creneau creneau = creneau(1, LocalDate.of(2026, 8, 1));
        creneau.setCouverturePause(true);
        stand.setOuvertures(List.of(
                new OuvertureStand(null, creneau.getDate(), LocalTime.of(10, 0), LocalTime.of(12, 0), null, 4)));

        FeasibilityReport report = analyzer.analyze(
                List.of(animateur("a1", "STRATEGIE"), animateur("a2", "STRATEGIE")), List.of(stand), List.of(creneau));

        assertThat(report.feasible()).isTrue();
    }

    @Test
    void laDemandeSuitEffectifMinPasEffectifMax() {
        // effectifMin = 2 (two seats generated), effectifMax = 4: two animateurs
        // are enough. Counting effectifMax would announce 2 missing.
        Stand stand = new Stand("stand-1", "stand-1", Set.of("STRATEGIE"), 2, 4, false);
        Creneau creneau = creneau(1, LocalDate.of(2026, 8, 1));

        FeasibilityReport report = analyzer.analyze(
                List.of(animateur("a1", "STRATEGIE"), animateur("a2", "STRATEGIE")), List.of(stand), List.of(creneau));

        assertThat(report.feasible()).isTrue();
        assertThat(report.manqueAnimateurs()).isZero();
    }

    @Test
    void nothingIsFeasibleWithoutAnyAnimateurEvenWithNothingToStaff() {
        // Nothing to list — no seat is short of anybody — yet « réalisable »
        // would be read as a green light on an edition that cannot be solved.
        FeasibilityReport report = analyzer.analyze(List.of(), List.of(), List.of());

        assertThat(report.feasible()).isFalse();
        assertThat(report.manqueAnimateurs()).isZero();
        assertThat(report.causes()).isEmpty();
        assertThat(report.totalCauses()).isZero();
        assertThat(report.message()).contains("Aucun animateur n'est saisi");
    }

    @Test
    void anEmptyRosterWithSeatsToFillIsNamedFirstAndTheTimeslotsStayListed() {
        Stand stand = stand("stand-1", 2, "STRATEGIE");
        Creneau creneau = creneau(1, LocalDate.of(2026, 8, 1));

        FeasibilityReport report = analyzer.analyze(List.of(), List.of(stand), List.of(creneau));

        assertThat(report.feasible()).isFalse();
        assertThat(report.causes()).singleElement().satisfies(cause -> {
            assertThat(cause.severite()).isEqualTo(SeveriteInfaisabilite.CRITIQUE);
            assertThat(cause.manque()).isEqualTo(2);
        });
        assertThat(report.message())
                .startsWith("Aucun animateur n'est saisi")
                .contains("1 cause bloquante a été détectée");
    }

    /**
     * Nothing to staff is not a green light either: an edition without any
     * timeslot, or whose stands open on none, gives a solve nothing to do, and
     * « réalisable » over it read as done.
     */
    @Test
    void anEditionWithoutAnyTimeslotIsNotFeasible() {
        FeasibilityReport report = analyzer.analyze(List.of(animateur("a1", "STRATEGIE")), List.of(), List.of());

        assertThat(report.feasible()).isFalse();
        assertThat(report.causes()).isEmpty();
        assertThat(report.message()).isEqualTo(FeasibilityAnalyzer.MESSAGE_SANS_CRENEAU);
    }

    @Test
    void anEditionWhoseStandsOpenOnNoTimeslotIsNotFeasible() {
        Stand ferme = stand("stand-1", 1, "STRATEGIE");
        ferme.setIndisponibilites(List.of(new dev.sylvain.planning.domain.IndisponibiliteStand(
                null, LocalDate.of(2026, 8, 1), LocalTime.of(9, 0), null, null)));

        FeasibilityReport report = analyzer.analyze(
                List.of(animateur("a1", "STRATEGIE")), List.of(ferme), List.of(creneau(1, LocalDate.of(2026, 8, 1))));

        assertThat(report.feasible()).isFalse();
        assertThat(report.causes()).isEmpty();
        assertThat(report.message()).isEqualTo(FeasibilityAnalyzer.MESSAGE_SANS_POSTE);
    }

    /** A minor holds a seat only beside an adult: a team of minors holds nothing. */
    @Test
    void minorsWithoutAnAdultHoldNoSeat() {
        Stand stand = stand("stand-1", 2, "STRATEGIE");
        Creneau creneau = creneau(1, LocalDate.of(2026, 8, 1));

        FeasibilityReport report = analyzer.analyze(
                List.of(mineur("m1", 2010), mineur("m2", 2010), mineur("m3", 2010)), List.of(stand), List.of(creneau));

        assertThat(report.feasible()).isFalse();
        assertThat(report.causes()).singleElement().satisfies(cause -> {
            assertThat(cause.demande()).isEqualTo(2);
            assertThat(cause.capacite()).isZero();
            assertThat(cause.severite()).isEqualTo(SeveriteInfaisabilite.CRITIQUE);
        });
    }

    /** One adult on the largest stand opens its other seats to minors; an adults-only stand opens none. */
    @Test
    void anAdultOpensTheOtherSeatsOfTheLargestStandToMinors() {
        Stand grand = stand("grand", 3, "STRATEGIE");
        Stand petit = stand("petit", 1, "STRATEGIE");
        Stand bar = new Stand("bar", "bar", Set.of("STRATEGIE"), 2, 2, true);
        Creneau creneau = creneau(1, LocalDate.of(2026, 8, 1));

        FeasibilityReport report = analyzer.analyze(
                List.of(animateur("a1", "STRATEGIE"), mineur("m1", 2010), mineur("m2", 2010), mineur("m3", 2010)),
                List.of(grand, petit, bar),
                List.of(creneau));

        // Six seats: the adult on the big stand opens two seats to minors, the third minor has nowhere to go.
        assertThat(report.causes()).singleElement().satisfies(cause -> {
            assertThat(cause.demande()).isEqualTo(6);
            assertThat(cause.capacite()).isEqualTo(3);
            assertThat(cause.manque()).isEqualTo(3);
        });
    }

    /** At night the minors do not count, even beside an adult. */
    @Test
    void minorsDoNotCountAtNight() {
        Stand stand = stand("stand-1", 2, "STRATEGIE");
        Creneau soiree = new Creneau(1L, 1, LocalDate.of(2026, 8, 1), LocalTime.of(21, 0), LocalTime.of(23, 30));

        FeasibilityReport report = analyzer.analyze(
                List.of(animateur("a1", "STRATEGIE"), mineur("m1", 2010)), List.of(stand), List.of(soiree));

        assertThat(report.causes())
                .singleElement()
                .satisfies(cause -> assertThat(cause.capacite()).isEqualTo(1));
    }

    @Test
    void contradictoryAdHocConstraintsAreReportedAsABlockingCause() {
        // Recorded before the entry-time check existed, or imported together:
        // nothing else would ever point at them (issue #84).
        Creneau creneau = creneau(1, LocalDate.of(2026, 8, 1));
        ContrainteAdHoc indisponibilite = contrainte("C1", TypeContrainteAdHoc.INDISPONIBILITE_FORCEE, creneau);
        ContrainteAdHoc forcee = contrainte("C2", TypeContrainteAdHoc.AFFECTATION_FORCEE, creneau);

        FeasibilityReport report = analyzer.analyze(
                List.of(animateur("a1", "STRATEGIE")),
                List.of(stand("stand-1", 1, "STRATEGIE")),
                List.of(creneau),
                List.of(indisponibilite, forcee));

        assertThat(report.feasible()).isFalse();
        assertThat(report.manqueAnimateurs()).isZero();
        assertThat(report.message())
                .contains("1 cause bloquante")
                .contains("C1")
                .contains("C2");

        CauseInfaisabilite cause = report.causes().getFirst();
        assertThat(cause.type()).isEqualTo(TypeCauseInfaisabilite.CONTRAINTES_AD_HOC_CONTRADICTOIRES);
        assertThat(cause.severite()).isEqualTo(SeveriteInfaisabilite.CRITIQUE);
        assertThat(cause.contrainteIds()).containsExactly("C1", "C2");
        assertThat(cause.standIds()).isEmpty();
    }

    @Test
    void aContradictionIsRankedBeforeAShortfallOfAnimateurs() {
        // Both are CRITIQUE, but one is fixed by deleting a line the user typed
        // and the other one takes recruiting.
        Creneau creneau = creneau(1, LocalDate.of(2026, 8, 1));
        FeasibilityReport report = analyzer.analyze(
                List.of(),
                List.of(stand("stand-1", 3, "STRATEGIE")),
                List.of(creneau),
                List.of(
                        contrainte("C1", TypeContrainteAdHoc.INDISPONIBILITE_FORCEE, creneau),
                        contrainte("C2", TypeContrainteAdHoc.AFFECTATION_FORCEE, creneau)));

        assertThat(report.causes())
                .extracting(CauseInfaisabilite::type)
                .containsExactly(
                        TypeCauseInfaisabilite.CONTRAINTES_AD_HOC_CONTRADICTOIRES,
                        TypeCauseInfaisabilite.CRENEAU_SOUS_EFFECTIF);
    }

    @Test
    void consistentAdHocConstraintsChangeNothing() {
        Creneau creneau = creneau(1, LocalDate.of(2026, 8, 1));

        FeasibilityReport report = analyzer.analyze(
                List.of(animateur("a1", "STRATEGIE")),
                List.of(stand("stand-1", 1, "STRATEGIE")),
                List.of(creneau),
                List.of(contrainte("C1", TypeContrainteAdHoc.AFFECTATION_FORCEE, creneau)));

        assertThat(report.feasible()).isTrue();
        assertThat(report.causes()).isEmpty();
    }

    /** A constraint targeting {@code a1} on {@code creneau}, the shape both contradiction tests need. */
    private static ContrainteAdHoc contrainte(String id, TypeContrainteAdHoc type, Creneau creneau) {
        ContrainteAdHoc contrainte = new ContrainteAdHoc(id, type);
        contrainte.setCreneau(creneau);
        Animateur animateur = new Animateur();
        animateur.setId("a1");
        contrainte.setAnimateursConcernes(List.of(animateur));
        return contrainte;
    }

    /**
     * {@code effectifMin} drives the demand: it is the number of seats
     * {@code ProblemBuilder.buildPostes} actually generates. {@code
     * effectifMax} is deliberately set higher so a regression back to counting
     * it would change the expected shortfalls.
     */
    private static Stand stand(String id, int effectifMin, String typologie) {
        return new Stand(id, id, Set.of(typologie), effectifMin, effectifMin + 2, false);
    }

    private static Creneau creneau(long id, LocalDate date) {
        return new Creneau(id, 1, date, LocalTime.of(10, 0), LocalTime.of(12, 0));
    }

    private static Animateur mineur(String id, int anneeDeNaissance) {
        Animateur animateur = new Animateur(id, id, id, LocalDate.of(anneeDeNaissance, 1, 1), false);
        animateur.setCompetences(java.util.Map.of("STRATEGIE", NiveauCompetence.AUTONOME));
        return animateur;
    }

    private static Animateur animateur(String id, String typologie) {
        Animateur animateur = new Animateur(id, id, id, LocalDate.of(1990, 1, 1), false);
        animateur.setCompetences(java.util.Map.of(typologie, NiveauCompetence.AUTONOME));
        return animateur;
    }
}
