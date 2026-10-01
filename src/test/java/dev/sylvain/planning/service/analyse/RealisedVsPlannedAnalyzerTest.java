package dev.sylvain.planning.service.analyse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.analyse.RealisedSource.Realised;
import dev.sylvain.planning.service.analyse.RealisedSource.RealisedNature;
import dev.sylvain.planning.service.analyse.RealisedSource.SeatAbsence;
import dev.sylvain.planning.service.analyse.RealisedVsPlanned.CellDetail;
import dev.sylvain.planning.service.analyse.RealisedVsPlanned.GapCounts;
import dev.sylvain.planning.service.analyse.RealisedVsPlanned.GapTotal;
import dev.sylvain.planning.service.analyse.RealisedVsPlanned.RealisedCell;
import dev.sylvain.planning.service.analyse.RealisedVsPlanned.RealisedDay;
import dev.sylvain.planning.service.analyse.RealisedVsPlanned.RealisedLine;
import dev.sylvain.planning.service.analyse.RealisedVsPlanned.RealisedOutcome;
import dev.sylvain.planning.service.analyse.RealisedVsPlannedAnalyzer.ChosenReference;
import dev.sylvain.planning.service.analyse.RealisedVsPlannedAnalyzer.DayReference;
import dev.sylvain.planning.service.analyse.RealisedVsPlannedAnalyzer.DaySpan;
import dev.sylvain.planning.service.analyse.RealisedVsPlannedAnalyzer.Publication;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The measure behind Réalisé vs planifié, on hand-built plans: what counts as
 * an absence, a replacement, an emptied seat or a removed one, which
 * publication a day is measured against, and that the totals are the sums of
 * the cells. Plain JUnit — the measure is a pure function.
 */
class RealisedVsPlannedAnalyzerTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Paris");
    private static final LocalDate SAMEDI = LocalDate.of(2026, 7, 11);
    private static final LocalDate DIMANCHE = LocalDate.of(2026, 7, 12);
    private static final LocalDate LUNDI = LocalDate.of(2026, 7, 13);
    private static final Instant PUBLIE_AVANT = Instant.parse("2026-07-01T10:00:00Z");

    private static final Animateur CAMILLE =
            new Animateur("camille", "Camille", "Durand", LocalDate.of(1990, 1, 1), false);
    private static final Animateur DOMINIQUE =
            new Animateur("dominique", "Dominique", "Petit", LocalDate.of(1991, 2, 2), false);
    private static final Animateur SASHA = new Animateur("sasha", "Sasha", "Roy", LocalDate.of(1992, 3, 3), false);

    private static final Stand CIRQUE = new Stand("cirque", "Cirque", Set.of("JEU", "MOTRICITE"), 1, 2, false);
    private static final Stand NINJA = new Stand("ninja", "Ninja", Set.of("MOTRICITE"), 1, 2, false);

    private static final Creneau SAMEDI_APREM = new Creneau(1L, 1, SAMEDI, LocalTime.of(14, 0), LocalTime.of(18, 0));
    private static final Creneau SAMEDI_MATIN = new Creneau(2L, 1, SAMEDI, LocalTime.of(10, 0), LocalTime.of(12, 0));
    private static final Creneau SAMEDI_NUIT = new Creneau(4L, 1, SAMEDI, LocalTime.of(22, 0), LocalTime.of(2, 0));
    private static final Creneau DIMANCHE_APREM =
            new Creneau(3L, 2, DIMANCHE, LocalTime.of(14, 0), LocalTime.of(18, 0));

    /** A plan built seat by seat; poste ids differ between the two sides, as two snapshots' do. */
    private static final class Plan {
        private final List<PosteAffectation> postes = new ArrayList<>();
        private final String prefix;

        Plan(String prefix) {
            this.prefix = prefix;
        }

        Plan seat(Stand stand, Creneau creneau, Animateur animateur) {
            return seat(stand, creneau, animateur, null, null);
        }

        Plan seat(Stand stand, Creneau creneau, Animateur animateur, LocalTime debut, LocalTime fin) {
            PosteAffectation poste = new PosteAffectation(prefix + postes.size(), stand, creneau);
            poste.setAnimateur(animateur);
            poste.setHeureDebutEffective(debut);
            poste.setHeureFinEffective(fin);
            postes.add(poste);
            return this;
        }

        PlanningEvenement build() {
            return new PlanningEvenement(SAMEDI, List.of(CAMILLE, DOMINIQUE, SASHA), List.copyOf(postes));
        }
    }

    private static Realised realised(Plan plan, SeatAbsence... absences) {
        return new Realised(plan.build(), Set.of(absences), RealisedNature.DECLARED);
    }

    private static DayReference day(LocalDate date, Plan reference) {
        return new DayReference(date, reference.build(), PUBLIE_AVANT, false);
    }

    private RealisedVsPlanned analyse(List<DayReference> days, Realised realised) {
        return RealisedVsPlannedAnalyzer.analyse(
                days, realised, true, true, LUNDI, Map.of("JEU", "Jeux", "MOTRICITE", "Motricité"));
    }

    private static GapCounts cell(RealisedVsPlanned report, String standId, LocalDate date) {
        return report.cells().stream()
                .filter(cell -> cell.standId().equals(standId) && cell.date().equals(date))
                .map(RealisedCell::counts)
                .findFirst()
                .orElseThrow();
    }

    @Test
    void anAbsenceRefilledCountsAsAnAbsenceAndAReplacement() {
        Plan publie = new Plan("p").seat(CIRQUE, SAMEDI_APREM, CAMILLE).seat(CIRQUE, SAMEDI_APREM, DOMINIQUE);
        Plan tenu = new Plan("r").seat(CIRQUE, SAMEDI_APREM, SASHA).seat(CIRQUE, SAMEDI_APREM, DOMINIQUE);

        GapCounts counts = cell(
                analyse(List.of(day(SAMEDI, publie)), realised(tenu, new SeatAbsence("camille", 1L))),
                "cirque",
                SAMEDI);

        assertThat(counts.publishedSeats()).isEqualTo(2);
        assertThat(counts.absences()).isEqualTo(1);
        assertThat(counts.replacements()).isEqualTo(1);
        assertThat(counts.emptySeats()).isZero();
        assertThat(counts.keptSeats()).isEqualTo(2);
        assertThat(counts.lostMinutes()).isZero();
        assertThat(counts.absenceRate()).isEqualTo(0.5);
    }

    @Test
    void anAbsenceLeftEmptyCountsAsAnAbsenceAndAnEmptySeat() {
        Plan publie = new Plan("p").seat(CIRQUE, SAMEDI_APREM, CAMILLE);
        Plan tenu = new Plan("r").seat(CIRQUE, SAMEDI_APREM, null);

        GapCounts counts = cell(
                analyse(List.of(day(SAMEDI, publie)), realised(tenu, new SeatAbsence("camille", 1L))),
                "cirque",
                SAMEDI);

        assertThat(counts.absences()).isEqualTo(1);
        assertThat(counts.replacements()).isZero();
        assertThat(counts.emptySeats()).isEqualTo(1);
        assertThat(counts.keptSeats()).isZero();
        assertThat(counts.publishedMinutes()).isEqualTo(240);
        assertThat(counts.realisedMinutes()).isZero();
        assertThat(counts.lostMinutes()).isEqualTo(240);
    }

    @Test
    void aHolderChangedWithoutAnAbsenceIsAReplacementOnly() {
        Plan publie = new Plan("p").seat(CIRQUE, SAMEDI_APREM, CAMILLE);
        Plan tenu = new Plan("r").seat(CIRQUE, SAMEDI_APREM, SASHA);

        GapCounts counts = cell(analyse(List.of(day(SAMEDI, publie)), realised(tenu)), "cirque", SAMEDI);

        assertThat(counts.absences()).isZero();
        assertThat(counts.replacements()).isEqualTo(1);
    }

    /** ADR 0066: the seat under way is split at « now », the absent person keeping what they held. */
    @Test
    void aSeatSplitOnTheDayIsAnAbsenceRefilledWhenSomebodyTookTheRemainder() {
        Plan publie = new Plan("p").seat(CIRQUE, SAMEDI_APREM, CAMILLE);
        Plan tenu = new Plan("r")
                .seat(CIRQUE, SAMEDI_APREM, CAMILLE, null, LocalTime.of(16, 0))
                .seat(CIRQUE, SAMEDI_APREM, SASHA, LocalTime.of(16, 0), null);
        Plan resteVide = new Plan("v")
                .seat(CIRQUE, SAMEDI_APREM, CAMILLE, null, LocalTime.of(16, 0))
                .seat(CIRQUE, SAMEDI_APREM, null, LocalTime.of(16, 0), null);

        GapCounts repourvu = cell(
                analyse(List.of(day(SAMEDI, publie)), realised(tenu, new SeatAbsence("camille", 1L))),
                "cirque",
                SAMEDI);
        GapCounts vide = cell(
                analyse(List.of(day(SAMEDI, publie)), realised(resteVide, new SeatAbsence("camille", 1L))),
                "cirque",
                SAMEDI);

        assertThat(repourvu.absences()).isEqualTo(1);
        assertThat(repourvu.replacements()).isEqualTo(1);
        assertThat(repourvu.addedSeats()).isZero();
        assertThat(repourvu.emptySeats()).isZero();
        assertThat(vide.absences()).isEqualTo(1);
        assertThat(vide.emptySeats()).isEqualTo(1);
        assertThat(vide.lostMinutes()).isEqualTo(120);
    }

    /**
     * Camille holds the morning and the afternoon of Cirque and is marked
     * absent at 11:00: the morning seat is split (ADR 0066), she keeps
     * 10-11, the rest of it and the whole afternoon come back empty. Two
     * absences, two seats left empty — the morning one for its remainder
     * only —, nothing removed and nothing added: the part she held is no new
     * seat, and the chair the split reshaped was not taken away.
     */
    @Test
    void aSplitSeatNextToAFreedOneIsTwoAbsencesNeverARemovalAndAnAddition() {
        Plan publie = new Plan("p").seat(CIRQUE, SAMEDI_MATIN, CAMILLE).seat(CIRQUE, SAMEDI_APREM, CAMILLE);
        Plan tenu = new Plan("r")
                .seat(CIRQUE, SAMEDI_MATIN, CAMILLE, null, LocalTime.of(11, 0))
                .seat(CIRQUE, SAMEDI_MATIN, null, LocalTime.of(11, 0), null)
                .seat(CIRQUE, SAMEDI_APREM, null);
        Realised realise = realised(tenu, new SeatAbsence("camille", 2L), new SeatAbsence("camille", 1L));

        GapCounts counts = cell(analyse(List.of(day(SAMEDI, publie)), realise), "cirque", SAMEDI);

        assertThat(counts.absences()).isEqualTo(2);
        assertThat(counts.removedSeats()).isZero();
        assertThat(counts.addedSeats()).isZero();
        assertThat(counts.emptySeats()).isEqualTo(2);
        assertThat(counts.realisedMinutes()).isEqualTo(60);
        assertThat(counts.lostMinutes()).isEqualTo(60 + 240);
        assertThat(RealisedVsPlannedAnalyzer.detail(day(SAMEDI, publie), "cirque", "Cirque", realise)
                        .lines())
                .extracting(RealisedLine::outcome)
                .containsExactly(RealisedOutcome.EMPTIED, RealisedOutcome.EMPTIED);
    }

    /** Same, the remainder of the morning taken by Sasha: one of the two absences is refilled. */
    @Test
    void aSplitSeatWhoseRemainderWasTakenNextToAFreedOneIsRefilled() {
        Plan publie = new Plan("p").seat(CIRQUE, SAMEDI_MATIN, CAMILLE).seat(CIRQUE, SAMEDI_APREM, CAMILLE);
        Plan tenu = new Plan("r")
                .seat(CIRQUE, SAMEDI_MATIN, CAMILLE, null, LocalTime.of(11, 0))
                .seat(CIRQUE, SAMEDI_MATIN, SASHA, LocalTime.of(11, 0), null)
                .seat(CIRQUE, SAMEDI_APREM, null);
        Realised realise = realised(tenu, new SeatAbsence("camille", 2L), new SeatAbsence("camille", 1L));

        GapCounts counts = cell(analyse(List.of(day(SAMEDI, publie)), realise), "cirque", SAMEDI);

        assertThat(counts.absences()).isEqualTo(2);
        assertThat(counts.replacements()).isEqualTo(1);
        assertThat(counts.emptySeats()).isEqualTo(1);
        assertThat(counts.removedSeats()).isZero();
        assertThat(counts.addedSeats()).isZero();
        assertThat(counts.lostMinutes()).isEqualTo(240);
    }

    /**
     * Camille moved from the morning to the afternoon seat, which nobody had
     * been announced on, and the morning chair stayed empty: that is a seat
     * left empty and one added, not the same seat at other hours.
     */
    @Test
    void aMoveToAnotherTimeslotOfTheStandLeavesTheFirstSeatEmpty() {
        Plan publie = new Plan("p").seat(CIRQUE, SAMEDI_MATIN, CAMILLE).seat(CIRQUE, SAMEDI_APREM, null);
        Plan tenu = new Plan("r").seat(CIRQUE, SAMEDI_MATIN, null).seat(CIRQUE, SAMEDI_APREM, CAMILLE);

        GapCounts counts = cell(analyse(List.of(day(SAMEDI, publie)), realised(tenu)), "cirque", SAMEDI);

        assertThat(counts.emptySeats()).isEqualTo(1);
        assertThat(counts.lostMinutes()).isEqualTo(120);
        assertThat(counts.keptSeats()).isZero();
        assertThat(counts.addedSeats()).isEqualTo(1);
        assertThat(counts.absences()).isZero();
    }

    @Test
    void aSeatAConsigneTookOutIsRemovedNeverAnAbsence() {
        Plan publie = new Plan("p").seat(CIRQUE, SAMEDI_APREM, CAMILLE).seat(CIRQUE, SAMEDI_MATIN, DOMINIQUE);
        // The afternoon band was closed: the chair is gone, not emptied.
        Plan tenu = new Plan("r").seat(CIRQUE, SAMEDI_MATIN, DOMINIQUE);

        RealisedVsPlanned report =
                analyse(List.of(day(SAMEDI, publie)), realised(tenu, new SeatAbsence("camille", 1L)));
        GapCounts counts = cell(report, "cirque", SAMEDI);

        assertThat(counts.removedSeats()).isEqualTo(1);
        assertThat(counts.absences()).isZero();
        assertThat(counts.emptySeats()).isZero();
        assertThat(counts.lostMinutes()).isZero();
        assertThat(counts.keptSeats()).isEqualTo(1);
    }

    @Test
    void hoursATrimmingConsigneMovedAreNoGap() {
        Plan publie = new Plan("p").seat(CIRQUE, SAMEDI_APREM, CAMILLE);
        Plan tenu = new Plan("r").seat(CIRQUE, SAMEDI_APREM, CAMILLE, null, LocalTime.of(16, 0));

        RealisedVsPlanned report = analyse(List.of(day(SAMEDI, publie)), realised(tenu));
        GapCounts counts = cell(report, "cirque", SAMEDI);

        assertThat(counts.absences()).isZero();
        assertThat(counts.emptySeats()).isZero();
        assertThat(counts.keptSeats()).isEqualTo(1);
        assertThat(counts.realisedMinutes()).isEqualTo(120);
        CellDetail detail = RealisedVsPlannedAnalyzer.detail(day(SAMEDI, publie), "cirque", "Cirque", realised(tenu));
        assertThat(detail.lines()).extracting(RealisedLine::outcome).containsExactly(RealisedOutcome.HOURS_CHANGED);
    }

    /** A timeslot deleted after the publication: the seat the snapshot kept has no chair left. */
    @Test
    void aTimeslotDeletedAfterThePublicationIsRemovedNotAnAbsence() {
        Creneau supprime = new Creneau(9L, 1, SAMEDI, LocalTime.of(18, 0), LocalTime.of(20, 0));
        Plan publie = new Plan("p").seat(NINJA, supprime, CAMILLE);
        Plan tenu = new Plan("r");

        GapCounts counts = cell(
                analyse(List.of(day(SAMEDI, publie)), realised(tenu, new SeatAbsence("camille", 9L))), "ninja", SAMEDI);

        assertThat(counts.removedSeats()).isEqualTo(1);
        assertThat(counts.absences()).isZero();
    }

    @Test
    void anUnannouncedSeatIsAdded() {
        Plan publie = new Plan("p").seat(CIRQUE, SAMEDI_APREM, CAMILLE);
        Plan tenu = new Plan("r").seat(CIRQUE, SAMEDI_APREM, CAMILLE).seat(CIRQUE, SAMEDI_APREM, SASHA);

        GapCounts counts = cell(analyse(List.of(day(SAMEDI, publie)), realised(tenu)), "cirque", SAMEDI);

        assertThat(counts.addedSeats()).isEqualTo(1);
        assertThat(counts.realisedMinutes()).isEqualTo(480);
        assertThat(counts.keptSeats()).isEqualTo(1);
    }

    @Test
    void aNightShiftBelongsToItsStartDayWithItsFullLength() {
        Plan publie = new Plan("p").seat(CIRQUE, SAMEDI_NUIT, CAMILLE);
        Plan tenu = new Plan("r").seat(CIRQUE, SAMEDI_NUIT, null);

        RealisedVsPlanned report =
                analyse(List.of(day(SAMEDI, publie)), realised(tenu, new SeatAbsence("camille", 4L)));

        assertThat(report.cells()).extracting(RealisedCell::date).containsExactly(SAMEDI);
        assertThat(cell(report, "cirque", SAMEDI).lostMinutes()).isEqualTo(240);
        assertThat(cell(report, "cirque", SAMEDI).absences()).isEqualTo(1);
    }

    /**
     * The republication of Saturday noon announced Sasha: Saturday stays
     * measured against what it had been promised, Sunday against the new
     * promise.
     */
    @Test
    void aRepublicationDuringTheEventDoesNotMoveTheDaysAlreadyStarted() {
        Publication avant = new Publication(1, PUBLIE_AVANT);
        Publication samediMidi =
                new Publication(2, SAMEDI.atTime(12, 0).atZone(ZONE).toInstant());
        List<Publication> publications = List.of(samediMidi, avant);

        ChosenReference samedi = RealisedVsPlannedAnalyzer.referenceFor(start(SAMEDI, 10), publications);
        ChosenReference dimanche = RealisedVsPlannedAnalyzer.referenceFor(start(DIMANCHE, 14), publications);
        assertThat(samedi.publication()).isEqualTo(avant);
        assertThat(samedi.late()).isFalse();
        assertThat(dimanche.publication()).isEqualTo(samediMidi);

        Plan premiere = new Plan("p").seat(CIRQUE, SAMEDI_APREM, CAMILLE).seat(CIRQUE, DIMANCHE_APREM, CAMILLE);
        Plan seconde = new Plan("s").seat(CIRQUE, SAMEDI_APREM, SASHA).seat(CIRQUE, DIMANCHE_APREM, SASHA);
        Plan tenu = new Plan("r").seat(CIRQUE, SAMEDI_APREM, SASHA).seat(CIRQUE, DIMANCHE_APREM, SASHA);
        Realised realise = realised(tenu, new SeatAbsence("camille", 1L), new SeatAbsence("camille", 3L));

        RealisedVsPlanned report = analyse(
                List.of(
                        new DayReference(SAMEDI, premiere.build(), avant.publishedAt(), false),
                        new DayReference(DIMANCHE, seconde.build(), samediMidi.publishedAt(), false)),
                realise);

        assertThat(cell(report, "cirque", SAMEDI).absences()).isEqualTo(1);
        assertThat(cell(report, "cirque", SAMEDI).replacements()).isEqualTo(1);
        assertThat(cell(report, "cirque", DIMANCHE).replacements()).isZero();
        assertThat(cell(report, "cirque", DIMANCHE).absences()).isZero();
    }

    @Test
    void aDayStartedBeforeAnyPublicationIsMeasuredAgainstTheFirstOneMarkedLate() {
        Publication samediMidi =
                new Publication(2, SAMEDI.atTime(12, 0).atZone(ZONE).toInstant());

        ChosenReference samedi = RealisedVsPlannedAnalyzer.referenceFor(start(SAMEDI, 10), List.of(samediMidi));

        assertThat(samedi.publication()).isEqualTo(samediMidi);
        assertThat(samedi.late()).isTrue();
        assertThat(RealisedVsPlannedAnalyzer.referenceFor(start(SAMEDI, 10), List.of()))
                .isNull();
    }

    /**
     * A day starts at its first timeslot, not at midnight: the republication
     * of Saturday 07:00, before the 10:00 opening, is what Saturday was
     * promised.
     */
    @Test
    void aRepublicationBeforeTheFirstTimeslotIsTheReferenceOfThatDay() {
        Map<LocalDate, DaySpan> spans = new HashMap<>();
        RealisedVsPlannedAnalyzer.addSpans(spans, List.of(SAMEDI_APREM, SAMEDI_MATIN, SAMEDI_NUIT, DIMANCHE_APREM));
        Publication avant = new Publication(1, PUBLIE_AVANT);
        Publication samediMatin =
                new Publication(2, SAMEDI.atTime(7, 0).atZone(ZONE).toInstant());

        ChosenReference samedi = RealisedVsPlannedAnalyzer.referenceFor(
                spans.get(SAMEDI).startInstant(ZONE), List.of(avant, samediMatin));

        assertThat(spans.get(SAMEDI).start()).isEqualTo(SAMEDI.atTime(10, 0));
        assertThat(samedi.publication()).isEqualTo(samediMatin);
        assertThat(samedi.late()).isFalse();
    }

    /** Saturday's night shift ends at 02:00 on Sunday: Saturday is not over before. */
    @Test
    void aDayIsOverOnlyOnceItsLastTimeslotHasEnded() {
        Map<LocalDate, DaySpan> spans = new HashMap<>();
        RealisedVsPlannedAnalyzer.addSpans(spans, List.of(SAMEDI_APREM, SAMEDI_MATIN, SAMEDI_NUIT));

        assertThat(spans.get(SAMEDI).end()).isEqualTo(DIMANCHE.atTime(2, 0));
        assertThat(spans.get(SAMEDI).isOverAt(DIMANCHE.atTime(1, 0))).isFalse();
        assertThat(spans.get(SAMEDI).isOverAt(DIMANCHE.atTime(2, 0))).isTrue();
    }

    /**
     * Saturday was measured against a publication made at noon on Saturday:
     * that publication already holds the morning's change, so the day is
     * drawn and flagged but left out of every total.
     */
    @Test
    void aDayMeasuredAgainstALateReferenceIsDrawnButNeverSummed() {
        Plan publie = new Plan("p").seat(CIRQUE, SAMEDI_APREM, CAMILLE).seat(CIRQUE, DIMANCHE_APREM, CAMILLE);
        Plan tenu = new Plan("r").seat(CIRQUE, SAMEDI_APREM, null).seat(CIRQUE, DIMANCHE_APREM, null);
        Realised realise = realised(tenu, new SeatAbsence("camille", 1L), new SeatAbsence("camille", 3L));

        RealisedVsPlanned report = analyse(
                List.of(
                        new DayReference(SAMEDI, publie.build(), PUBLIE_AVANT, true),
                        new DayReference(DIMANCHE, publie.build(), PUBLIE_AVANT, false)),
                realise);

        assertThat(report.days())
                .extracting(RealisedDay::date, RealisedDay::counted, RealisedDay::lateReference)
                .containsExactly(tuple(SAMEDI, false, true), tuple(DIMANCHE, true, false));
        assertThat(cell(report, "cirque", SAMEDI).absences()).isEqualTo(1);
        assertThat(report.event()).isEqualTo(cell(report, "cirque", DIMANCHE));
        assertThat(report.byDay()).extracting(GapTotal::key).containsExactly(DIMANCHE.toString());
        assertThat(total(report.byStand(), "cirque")).isEqualTo(cell(report, "cirque", DIMANCHE));
        assertThat(total(report.byTypologie(), "JEU")).isEqualTo(cell(report, "cirque", DIMANCHE));
        assertThat(RealisedVsPlannedAnalyzer.csv(report))
                .contains("2026-07-11;Cirque;1;0;1;0;1;0;0;4,00;0,00;4,00;non")
                .contains("2026-07-12;Cirque;1;0;1;0;1;0;0;4,00;0,00;4,00;oui");
    }

    @Test
    void withoutAnyPublicationNothingIsCountedAndTheReportSaysSo() {
        Plan tenu = new Plan("r").seat(CIRQUE, SAMEDI_APREM, CAMILLE);

        RealisedVsPlanned report = RealisedVsPlannedAnalyzer.analyse(
                List.of(DayReference.none(SAMEDI)), realised(tenu), false, true, LUNDI, Map.of());

        assertThat(report.referenceAvailable()).isFalse();
        assertThat(report.cells()).isEmpty();
        assertThat(report.days())
                .singleElement()
                .satisfies(day -> assertThat(day.counted()).isFalse());
        assertThat(report.event()).isEqualTo(GapCounts.ZERO);
    }

    @Test
    void theTotalsAreTheSumsOfTheCells() {
        Plan publie = new Plan("p")
                .seat(CIRQUE, SAMEDI_APREM, CAMILLE)
                .seat(CIRQUE, SAMEDI_APREM, DOMINIQUE)
                .seat(NINJA, SAMEDI_MATIN, SASHA)
                .seat(CIRQUE, DIMANCHE_APREM, CAMILLE)
                .seat(NINJA, DIMANCHE_APREM, DOMINIQUE);
        Plan tenu = new Plan("r")
                .seat(CIRQUE, SAMEDI_APREM, SASHA)
                .seat(CIRQUE, SAMEDI_APREM, DOMINIQUE)
                .seat(NINJA, SAMEDI_MATIN, null)
                .seat(CIRQUE, DIMANCHE_APREM, CAMILLE)
                .seat(NINJA, DIMANCHE_APREM, SASHA);
        Realised realise = realised(tenu, new SeatAbsence("camille", 1L), new SeatAbsence("sasha", 2L));

        RealisedVsPlanned report = analyse(List.of(day(SAMEDI, publie), day(DIMANCHE, publie)), realise);

        GapCounts somme = report.cells().stream().map(RealisedCell::counts).reduce(GapCounts.ZERO, GapCounts::add);
        assertThat(report.event()).isEqualTo(somme);
        assertThat(report.byStand().stream().map(GapTotal::counts).reduce(GapCounts.ZERO, GapCounts::add))
                .isEqualTo(somme);
        assertThat(report.byDay().stream().map(GapTotal::counts).reduce(GapCounts.ZERO, GapCounts::add))
                .isEqualTo(somme);
        for (GapTotal parStand : report.byStand()) {
            assertThat(parStand.counts())
                    .isEqualTo(report.cells().stream()
                            .filter(cell -> cell.standId().equals(parStand.key()))
                            .map(RealisedCell::counts)
                            .reduce(GapCounts.ZERO, GapCounts::add));
        }
        // Cirque offers both categories and counts under each; Ninja only under Motricité.
        GapCounts cirque = total(report.byStand(), "cirque");
        GapCounts ninja = total(report.byStand(), "ninja");
        assertThat(total(report.byTypologie(), "JEU")).isEqualTo(cirque);
        assertThat(total(report.byTypologie(), "MOTRICITE")).isEqualTo(cirque.add(ninja));
        assertThat(report.byTypologie()).extracting(GapTotal::label).containsExactly("Jeux", "Motricité");
        assertThat(somme.publishedSeats()).isEqualTo(5);
        assertThat(somme.absences()).isEqualTo(2);
        assertThat(somme.replacements()).isEqualTo(2);
        assertThat(somme.emptySeats()).isEqualTo(1);
    }

    @Test
    void theCsvCarriesStandsDaysAndCountersButNoPerson() {
        Plan publie = new Plan("p").seat(CIRQUE, SAMEDI_APREM, CAMILLE).seat(NINJA, SAMEDI_MATIN, DOMINIQUE);
        Plan tenu = new Plan("r").seat(CIRQUE, SAMEDI_APREM, SASHA).seat(NINJA, SAMEDI_MATIN, null);

        String csv = RealisedVsPlannedAnalyzer.csv(analyse(
                List.of(day(SAMEDI, publie)),
                realised(tenu, new SeatAbsence("camille", 1L), new SeatAbsence("dominique", 2L))));

        assertThat(csv.lines().findFirst().orElseThrow()).startsWith("jour;stand;sieges publies");
        assertThat(csv)
                .contains("2026-07-11;Cirque;1;1;1;1;0;0;0;4,00;4,00;0,00")
                .contains("2026-07-11;Ninja;1;0;1;0;1;0;0;2,00;0,00;2,00");
        for (Animateur animateur : List.of(CAMILLE, DOMINIQUE, SASHA)) {
            assertThat(csv)
                    .doesNotContain(animateur.getId())
                    .doesNotContain(animateur.getNom())
                    .doesNotContain(animateur.getPrenom());
        }
    }

    @Test
    void theDetailOfACellNamesTheHoldersOfItsLines() {
        Plan publie = new Plan("p").seat(CIRQUE, SAMEDI_APREM, CAMILLE).seat(NINJA, SAMEDI_MATIN, DOMINIQUE);
        Plan tenu = new Plan("r").seat(CIRQUE, SAMEDI_APREM, SASHA).seat(NINJA, SAMEDI_MATIN, DOMINIQUE);

        CellDetail detail = RealisedVsPlannedAnalyzer.detail(
                day(SAMEDI, publie), "cirque", "Cirque", realised(tenu, new SeatAbsence("camille", 1L)));

        assertThat(detail.referenceAvailable()).isTrue();
        assertThat(detail.lines()).singleElement().satisfies(ligne -> {
            assertThat(ligne.outcome()).isEqualTo(RealisedOutcome.REPLACED);
            assertThat(ligne.absence()).isTrue();
            assertThat(ligne.seat().avant().animateurId()).isEqualTo("camille");
            assertThat(ligne.seat().apres().animateurId()).isEqualTo("sasha");
        });
        assertThat(detail.counts().absences()).isEqualTo(1);
    }

    private static Instant start(LocalDate date, int hour) {
        return date.atTime(hour, 0).atZone(ZONE).toInstant();
    }

    private static GapCounts total(List<GapTotal> totals, String key) {
        return totals.stream()
                .filter(total -> total.key().equals(key))
                .map(GapTotal::counts)
                .findFirst()
                .orElseThrow();
    }
}
