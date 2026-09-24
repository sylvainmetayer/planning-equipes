package dev.sylvain.planning.service.analyse;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.IndisponibiliteStand;
import dev.sylvain.planning.domain.NiveauCompetence;
import dev.sylvain.planning.domain.OuvertureStand;
import dev.sylvain.planning.domain.PastHorizon;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypeContrainteAdHoc;
import dev.sylvain.planning.service.analyse.FeasibilityAnalyzer.CauseInfaisabilite;
import dev.sylvain.planning.service.analyse.FeasibilityAnalyzer.FeasibilityReport;
import dev.sylvain.planning.service.analyse.FeasibilityAnalyzer.SeveriteInfaisabilite;
import dev.sylvain.planning.service.analyse.FeasibilityAnalyzer.TypeCauseInfaisabilite;
import dev.sylvain.planning.service.diagnostic.MatchFacts;
import dev.sylvain.planning.service.solve.FrozenPast;
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

    /**
     * With the supervision of minors asked for, a minor holds a seat only
     * beside an adult: a team of minors holds nothing. The catalogue ships the
     * rule off (issue #595), so the estimate is told to apply it.
     */
    @Test
    void minorsWithoutAnAdultHoldNoSeatWhenSupervisionIsAskedFor() {
        Stand stand = stand("stand-1", 2, "STRATEGIE");
        Creneau creneau = creneau(1, LocalDate.of(2026, 8, 1));

        FeasibilityReport report = analyzer.analyze(
                List.of(mineur("m1", 2010), mineur("m2", 2010), mineur("m3", 2010)),
                List.of(stand),
                List.of(creneau),
                List.of(),
                true);

        assertThat(report.feasible()).isFalse();
        assertThat(report.causes()).singleElement().satisfies(cause -> {
            assertThat(cause.demande()).isEqualTo(2);
            assertThat(cause.capacite()).isZero();
            assertThat(cause.severite()).isEqualTo(SeveriteInfaisabilite.CRITIQUE);
        });
    }

    /**
     * Without that rule — the catalogue's own state — the same three minors
     * hold the two seats: the estimate must not keep pairing them off with an
     * adult nobody requires, or it calls a perfectly staffed day impossible.
     */
    @Test
    void minorsWithoutAnAdultHoldTheSeatsWhenSupervisionIsNotAskedFor() {
        Stand stand = stand("stand-1", 2, "STRATEGIE");
        Creneau creneau = creneau(1, LocalDate.of(2026, 8, 1));

        FeasibilityReport report = analyzer.analyze(
                List.of(mineur("m1", 2010), mineur("m2", 2010), mineur("m3", 2010)), List.of(stand), List.of(creneau));

        assertThat(report.feasible()).isTrue();
        assertThat(report.causes()).isEmpty();
    }

    /**
     * The pairing rule is off, and `standReserveAuxMajeurs` is not: it is a
     * hard rule of its own, still active. A créneau whose only open stand is
     * adults-only, staffed solely by minors, is impossible — and the estimate
     * said « fully staffed », because the short-circuit that skips the pairing
     * skipped the adults-only filter with it.
     */
    @Test
    void minorsHoldNoSeatOnAnAdultsOnlyStandEvenWithoutSupervision() {
        Stand bar = new Stand("bar", "bar", Set.of("STRATEGIE"), 2, 2, true);
        Creneau creneau = creneau(1, LocalDate.of(2026, 8, 1));

        FeasibilityReport report = analyzer.analyze(
                List.of(mineur("m1", 2010), mineur("m2", 2010), mineur("m3", 2010)), List.of(bar), List.of(creneau));

        assertThat(report.feasible()).isFalse();
        assertThat(report.causes()).singleElement().satisfies(cause -> {
            assertThat(cause.demande()).isEqualTo(2);
            assertThat(cause.capacite()).isZero();
        });
    }

    /** The same minors do hold the seats of the stand next door, which is open to them. */
    @Test
    void minorsHoldTheSeatsOfTheStandThatIsNotReservedToAdults() {
        Stand bar = new Stand("bar", "bar", Set.of("STRATEGIE"), 2, 2, true);
        Stand ouvert = stand("ouvert", 2, "STRATEGIE");
        Creneau creneau = creneau(1, LocalDate.of(2026, 8, 1));

        FeasibilityReport report = analyzer.analyze(
                List.of(mineur("m1", 2010), mineur("m2", 2010), mineur("m3", 2010), mineur("m4", 2010)),
                List.of(bar, ouvert),
                List.of(creneau));

        // Four seats asked for, and only the two of the open stand can be held.
        assertThat(report.causes()).singleElement().satisfies(cause -> {
            assertThat(cause.demande()).isEqualTo(4);
            assertThat(cause.capacite()).isEqualTo(2);
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
                List.of(creneau),
                List.of(),
                true);

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
    /** The playbook of a shortfall opens the bench on that very timeslot first, the skills of its stand next. */
    @Test
    void aShortfallCarriesItsPlaybookPositionedOnTheTimeslot() {
        Stand stand = stand("stand-1", 5, "STRATEGIE");
        Creneau creneau = creneau(1, LocalDate.of(2026, 8, 1));

        CauseInfaisabilite cause = analyzer.analyze(
                        List.of(animateur("a1", "STRATEGIE")), List.of(stand), List.of(creneau))
                .causes()
                .getFirst();

        assertThat(cause.actions())
                .extracting(dev.sylvain.planning.service.diagnostic.BlockerPlaybook.ActionType::code)
                .containsExactly("VOIR_BANC", "AJOUTER_COMPETENCE", "BAISSER_EFFECTIF", "REVOIR_INDISPONIBILITES");
        assertThat(cause.actions().getFirst().route()).isEqualTo("/diagnostic");
        assertThat(cause.actions().getFirst().parametres())
                .containsEntry("onglet", "banc")
                .containsEntry("creneau", "1");
        assertThat(cause.actions().get(1).parametres()).containsEntry("typologies", "STRATEGIE");
        assertThat(cause.actions().get(2).parametres()).containsEntry("stand", "stand-1");
    }

    /** A shortfall on a timeslot already started offers nothing that would rewrite a worked day, and says so. */
    @Test
    void aShortfallOnAStartedDayOffersNoGestureThatWouldChangeIt() {
        Stand stand = stand("stand-1", 5, "STRATEGIE");
        Creneau creneau = creneau(1, LocalDate.of(2026, 8, 1));

        CauseInfaisabilite cause = analyzer.analyze(
                        List.of(animateur("a1", "STRATEGIE")),
                        List.of(stand),
                        List.of(creneau),
                        List.of(),
                        false,
                        new FeasibilityAnalyzer.PlanContext(
                                List.of(),
                                Set::of,
                                new dev.sylvain.planning.domain.PastHorizon(
                                        LocalDate.of(2026, 8, 1), LocalTime.of(11, 0))))
                .causes()
                .getFirst();

        assertThat(cause.actions()).singleElement().satisfies(action -> {
            assertThat(action.code()).isEqualTo("JOURNEE_FIGEE");
            assertThat(action.explication()).contains("figé");
            assertThat(action.parametres()).containsEntry("date", "2026-08-01");
        });
    }

    /**
     * A 09:00-13:00 timeslot on a stand opening at 11:00 is still ahead at
     * 10:00: its seats start at 11:00, the freeze reads that start, and the
     * cause keeps its gestures instead of calling the day frozen.
     */
    @Test
    void aShortfallOnATimeslotWhoseStandOpensLaterIsNotFrozenBeforeTheOpening() {
        LocalDate day = LocalDate.of(2026, 8, 1);
        Stand stand = stand("stand-1", 5, "STRATEGIE");
        stand.setOuvertures(List.of(new OuvertureStand(null, day, LocalTime.of(11, 0), LocalTime.of(13, 0), null)));
        Creneau creneau = new Creneau(1L, 1, day, LocalTime.of(9, 0), LocalTime.of(13, 0));

        CauseInfaisabilite atTen = shortfallAt(stand, creneau, new PastHorizon(day, LocalTime.of(10, 0)));
        CauseInfaisabilite atEleven = shortfallAt(stand, creneau, new PastHorizon(day, LocalTime.of(11, 0)));

        assertThat(atTen.actions())
                .extracting(dev.sylvain.planning.service.diagnostic.BlockerPlaybook.ActionType::code)
                .doesNotContain("JOURNEE_FIGEE")
                .contains("VOIR_BANC");
        assertThat(atEleven.actions())
                .extracting(dev.sylvain.planning.service.diagnostic.BlockerPlaybook.ActionType::code)
                .containsExactly("JOURNEE_FIGEE");
    }

    private CauseInfaisabilite shortfallAt(Stand stand, Creneau creneau, PastHorizon horizon) {
        return analyzer.analyze(
                        List.of(animateur("a1", "STRATEGIE")),
                        List.of(stand),
                        List.of(creneau),
                        List.of(),
                        false,
                        new FeasibilityAnalyzer.PlanContext(List.of(), Set::of, horizon))
                .causes()
                .getFirst();
    }

    /** A rule is in the frozen past only when every one of its matches sits on seats already past. */
    @Test
    void aRuleIsInTheFrozenPastOnlyWhenEveryMatchSitsOnPastSeats() {
        PastHorizon horizon = new PastHorizon(LocalDate.of(2026, 8, 2), LocalTime.of(11, 0));
        Creneau yesterday = creneau(1, LocalDate.of(2026, 8, 1));
        Creneau thisMorning = creneau(2, LocalDate.of(2026, 8, 2));
        Creneau tomorrow = creneau(3, LocalDate.of(2026, 8, 3));
        PosteAffectation seatYesterday = seat("p1", yesterday, true);
        PosteAffectation seatThisMorning = seat("p2", thisMorning, true);
        PosteAffectation seatTomorrow = seat("p3", tomorrow, false);
        var timeslotFrozen = PlanningDiagnosticService.frozenTimeslots(
                List.of(seatYesterday, seatThisMorning, seatTomorrow), horizon);
        var started = List.of(new MatchFacts(List.of(seatYesterday)), new MatchFacts(List.of(List.of(thisMorning))));

        assertThat(PlanningDiagnosticService.onlyStartedTimeslots(started, horizon, timeslotFrozen))
                .isTrue();
        assertThat(PlanningDiagnosticService.onlyStartedTimeslots(
                        List.of(new MatchFacts(List.of(yesterday)), new MatchFacts(List.of(seatTomorrow))),
                        horizon,
                        timeslotFrozen))
                .as("one breach still to come")
                .isFalse();
        assertThat(PlanningDiagnosticService.onlyStartedTimeslots(
                        List.of(new MatchFacts(List.of("agrégat"))), horizon, timeslotFrozen))
                .as("a match naming no timeslot")
                .isFalse();
        assertThat(PlanningDiagnosticService.onlyStartedTimeslots(started, null, timeslotFrozen))
                .isFalse();
    }

    /**
     * The rule's reading follows the seat's own freeze: at 10:00, a seat of a
     * 09:00-13:00 timeslot whose stand opens at 11:00 is not past, so neither
     * the seat nor its timeslot is read as frozen — although the timeslot
     * itself started at 09:00.
     */
    @Test
    void aRuleOnASeatStartingLaterThanItsTimeslotIsNotFrozenBeforeTheSeatStarts() {
        LocalDate day = LocalDate.of(2026, 8, 1);
        PastHorizon horizon = new PastHorizon(day, LocalTime.of(10, 0));
        Creneau creneau = new Creneau(1L, 1, day, LocalTime.of(9, 0), LocalTime.of(13, 0));
        PosteAffectation seat = new PosteAffectation("p1", stand("stand-1", 1, "STRATEGIE"), creneau);
        seat.setHeureDebutEffective(LocalTime.of(11, 0));
        seat.setHeureFinEffective(LocalTime.of(13, 0));
        FrozenPast.mark(List.of(seat), horizon);
        var timeslotFrozen = PlanningDiagnosticService.frozenTimeslots(List.of(seat), horizon);

        assertThat(seat.isPasse()).isFalse();
        assertThat(PlanningDiagnosticService.onlyStartedTimeslots(
                        List.of(new MatchFacts(List.of(seat))), horizon, timeslotFrozen))
                .isFalse();
        assertThat(PlanningDiagnosticService.onlyStartedTimeslots(
                        List.of(new MatchFacts(List.of(creneau))), horizon, timeslotFrozen))
                .isFalse();
    }

    /** A rule's actions open on its first breach naming a timeslot: that day, that stand, that holder. */
    @Test
    void aRuleIsPositionedOnItsFirstBreachNamingATimeslot() {
        Creneau creneau = creneau(7, LocalDate.of(2026, 8, 3));
        PosteAffectation seat = seat("p1", creneau, false);
        seat.setAnimateur(animateur("a1", "STRATEGIE"));

        var position = PlanningDiagnosticService.positionOf(
                List.of(new MatchFacts(List.of("agrégat")), new MatchFacts(List.of(seat))), false);

        assertThat(position.creneauId()).isEqualTo(7L);
        assertThat(position.date()).isEqualTo(LocalDate.of(2026, 8, 3));
        assertThat(position.standIds()).containsExactly("stand-1");
        assertThat(position.typologieIds()).containsExactly("STRATEGIE");
        assertThat(position.animateurIds()).containsExactly("a1");
        assertThat(position.frozenPast()).isFalse();
        assertThat(PlanningDiagnosticService.positionOf(List.of(new MatchFacts(List.of("agrégat"))), true))
                .satisfies(bare -> {
                    assertThat(bare.date()).isNull();
                    assertThat(bare.frozenPast()).isTrue();
                });
    }

    private static PosteAffectation seat(String id, Creneau creneau, boolean past) {
        PosteAffectation seat = new PosteAffectation(id, stand("stand-1", 1, "STRATEGIE"), creneau);
        seat.setPasse(past);
        return seat;
    }

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
