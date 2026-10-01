package dev.sylvain.planning.service.analyse;

import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.analyse.RealisedSource.Realised;
import dev.sylvain.planning.service.analyse.RealisedVsPlanned.CellDetail;
import dev.sylvain.planning.service.analyse.RealisedVsPlanned.GapCounts;
import dev.sylvain.planning.service.analyse.RealisedVsPlanned.GapTotal;
import dev.sylvain.planning.service.analyse.RealisedVsPlanned.RealisedCell;
import dev.sylvain.planning.service.analyse.RealisedVsPlanned.RealisedDay;
import dev.sylvain.planning.service.analyse.RealisedVsPlanned.RealisedLine;
import dev.sylvain.planning.service.analyse.RealisedVsPlanned.RealisedOutcome;
import dev.sylvain.planning.service.espace.TimeslotWindows;
import dev.sylvain.planning.service.journee.ChangementsJournee.SeatChangeType;
import dev.sylvain.planning.service.journee.ChangementsJournee.SeatLine;
import dev.sylvain.planning.service.journee.ChangementsJourneeService;
import dev.sylvain.planning.service.referentiel.CsvFormulaGuard;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * The measure behind Réalisé vs planifié, on plans already read — pure, so
 * every rule of it is tested on hand-built plans.
 *
 * <p><b>It is not a comparison of its own.</b> A day is compared by
 * {@link ChangementsJourneeService#compareSeats}, the seat half of the
 * Journée's Changements rendering, against the publication in force when the
 * day started. What this class adds is the reading of each line that
 * comparison returns — was the seat emptied or taken out, was its holder
 * missing, was it refilled — and the sums.</p>
 *
 * <ul>
 *   <li>a seat held by somebody else ({@code REMPLACE}) is a replacement, and
 *       an absence too when its published holder was recorded missing on it;</li>
 *   <li>a seat nobody holds any more ({@code RETIRE}) is <b>emptied</b> when its
 *       cell still has the chair, <b>removed</b> when the chair itself is gone —
 *       a consigne closed the band, the timeslot was deleted — and only an
 *       emptied seat can be an absence;</li>
 *   <li>a seat its holder kept on other hours is <b>the same seat reshaped</b>
 *       only when the hours overlap and the chair of the published hours is
 *       gone: the seat the mode jour J split at « now » (ADR 0066), the hours
 *       a consigne trimmed. The comparison folds such a pair into one
 *       {@code HORAIRES} line only when the person has one seat on each side
 *       of the stand, so the pairs it leaves apart — a split seat next to a
 *       freed one — are matched here, and a {@code HORAIRES} line that is
 *       really a move to another chair of the stand — the published one still
 *       there — is read back as the seat it left and the seat it took;</li>
 *   <li>a reshaped seat whose holder was recorded missing is an absence,
 *       refilled when somebody took the remainder, <b>emptied</b> otherwise —
 *       the part they held counts in the realised minutes, only the remainder
 *       in the lost ones; for anybody else, hours a consigne moved, which is
 *       no gap;</li>
 *   <li>a seat nobody had been announced on ({@code NOUVEAU}) is added, unless
 *       it is that remainder.</li>
 * </ul>
 *
 * <p>A day whose reference was published after it started is shown, never
 * counted: that publication already holds the day's changes, and measured
 * against it the day would read as held. Its cells are drawn, the totals,
 * the CSV's counted column and the frozen measure leave it out.</p>
 *
 * <p>A shift crossing midnight belongs to its start day, as every seat of the
 * application does: its timeslot carries that date.</p>
 */
public final class RealisedVsPlannedAnalyzer {

    private static final int MINUTES_PER_DAY = 24 * 60;

    private RealisedVsPlannedAnalyzer() {}

    /**
     * One elapsed day and the published plan it is measured against.
     *
     * @param plan          the publication's plan, {@code null} when no
     *                      publication can stand for the day — the day is then
     *                      shown and not counted
     * @param publishedAt   when it was published
     * @param lateReference true when it was published after the day started:
     *                      the day is shown, flagged, and not counted
     * @param snapshotId    the publication's snapshot, {@code null} without one
     */
    public record DayReference(
            LocalDate date, PlanningEvenement plan, Instant publishedAt, boolean lateReference, Long snapshotId) {

        /** A reference whose snapshot is not tracked — what the tests build by hand. */
        public DayReference(LocalDate date, PlanningEvenement plan, Instant publishedAt, boolean lateReference) {
            this(date, plan, publishedAt, lateReference, null);
        }

        static DayReference none(LocalDate date) {
            return new DayReference(date, null, null, false, null);
        }

        /** Whether the day enters the totals: a reference in force when it started. */
        public boolean counted() {
            return plan != null && !lateReference;
        }
    }

    /** A publication as the choice of a day's reference reads it. */
    public record Publication(long snapshotId, Instant publishedAt) {}

    /**
     * The publication a day is measured against.
     *
     * @param late true when it was published after the day started
     */
    public record ChosenReference(Publication publication, boolean late) {}

    /**
     * When a day starts and ends: the start of its first timeslot, the end of
     * its last — past midnight for a night shift, which belongs to the day it
     * starts on.
     */
    public record DaySpan(LocalDate date, LocalDateTime start, LocalDateTime end) {

        DaySpan merge(DaySpan other) {
            return new DaySpan(
                    date, start.isBefore(other.start) ? start : other.start, end.isAfter(other.end) ? end : other.end);
        }

        /**
         * Whether the day is over at {@code now}: its last timeslot has ended.
         * Not midnight — a 22:00-02:00 shift is still being held at one in
         * the morning, and its day is not elapsed until it ends.
         */
        public boolean isOverAt(LocalDateTime now) {
            return !end.isAfter(now);
        }

        /** The instant the day starts, which is what « in force when the day started » is read at. */
        public Instant startInstant(ZoneId zone) {
            return start.atZone(zone).toInstant();
        }
    }

    /**
     * Adds the span of every timeslot of {@code creneaux} to {@code spans}, by
     * date. A day starts at its first timeslot, as the mode jour J and the
     * home screen count it (« la journée commence à son premier créneau »),
     * not at midnight: a republication at 07:00 before a 09:00 opening is
     * what that day was promised.
     */
    public static void addSpans(Map<LocalDate, DaySpan> spans, Collection<Creneau> creneaux) {
        for (Creneau creneau : creneaux) {
            if (creneau == null
                    || creneau.getDate() == null
                    || creneau.getHeureDebut() == null
                    || creneau.getHeureFin() == null) {
                continue;
            }
            LocalDateTime[] fenetre = TimeslotWindows.window(creneau);
            spans.merge(creneau.getDate(), new DaySpan(creneau.getDate(), fenetre[0], fenetre[1]), DaySpan::merge);
        }
    }

    /** Same, over the timeslots a plan's seats stand on — those of a publication may have been deleted since. */
    public static void addSpans(Map<LocalDate, DaySpan> spans, PlanningEvenement plan) {
        if (plan == null || plan.getPostes() == null) {
            return;
        }
        addSpans(
                spans,
                plan.getPostes().stream()
                        .map(PosteAffectation::getCreneau)
                        .filter(Objects::nonNull)
                        .toList());
    }

    /**
     * The publication a day is measured against: the last one made before the
     * day started — a republication in the middle of the event leaves the days
     * already started measured against what they had been promised. When
     * nothing had been published by then, the first publication made after
     * stands in, marked late; {@code null} only when nothing was ever
     * published.
     *
     * @param dayStart the start of the day's first timeslot
     * @return the publication and whether it is late, or {@code null}
     */
    public static ChosenReference referenceFor(Instant dayStart, List<Publication> publications) {
        Publication avant = null;
        Publication apres = null;
        for (Publication publication : publications) {
            if (publication.publishedAt() == null) {
                continue;
            }
            if (!publication.publishedAt().isAfter(dayStart)) {
                if (avant == null || isLater(publication, avant)) {
                    avant = publication;
                }
            } else if (apres == null || isLater(apres, publication)) {
                apres = publication;
            }
        }
        if (avant != null) {
            return new ChosenReference(avant, false);
        }
        return apres == null ? null : new ChosenReference(apres, true);
    }

    private static boolean isLater(Publication a, Publication b) {
        int byInstant = a.publishedAt().compareTo(b.publishedAt());
        return byInstant > 0 || (byInstant == 0 && a.snapshotId() > b.snapshotId());
    }

    /**
     * The whole report.
     *
     * @param days            every elapsed day, in order, with its reference
     * @param everPublished   whether anything was ever published
     * @param typologieLabels the label of each game category, by id
     */
    public static RealisedVsPlanned analyse(
            List<DayReference> days,
            Realised realised,
            boolean everPublished,
            boolean frozenPast,
            LocalDate today,
            Map<String, String> typologieLabels) {
        List<RealisedDay> dayRows = new ArrayList<>();
        List<RealisedCell> cells = new ArrayList<>();
        Map<String, Stand> stands = new HashMap<>();
        Set<LocalDate> counted = new HashSet<>();
        for (DayReference day : days) {
            dayRows.add(new RealisedDay(day.date(), day.counted(), day.publishedAt(), day.lateReference()));
            if (day.plan() == null) {
                continue;
            }
            if (day.counted()) {
                counted.add(day.date());
            }
            indexStands(day.plan(), stands);
            addCells(day, realised, cells);
        }
        cells.sort(Comparator.comparing(RealisedCell::date)
                .thenComparing(RealisedCell::standNom, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(RealisedCell::standId));

        // Read last, so today's referential wins: the game categories a stand offers now.
        indexStands(realised.plan(), stands);
        Map<String, GapTotal> totalsByStand = new TreeMap<>();
        Map<LocalDate, GapCounts> totalsByDay = new TreeMap<>();
        Map<String, GapCounts> totalsByTypologie = new TreeMap<>();
        GapCounts event = GapCounts.ZERO;
        for (RealisedCell cell : cells) {
            if (!counted.contains(cell.date())) {
                // A late reference already holds the day's changes: drawn, never summed.
                continue;
            }
            totalsByStand.merge(
                    cell.standId(),
                    new GapTotal(cell.standId(), cell.standNom(), cell.counts()),
                    (a, b) -> new GapTotal(a.key(), a.label(), a.counts().add(b.counts())));
            totalsByDay.merge(cell.date(), cell.counts(), GapCounts::add);
            addToTypologies(totalsByTypologie, stands.get(cell.standId()), cell.counts());
            event = event.add(cell.counts());
        }
        List<GapTotal> byStand = totalsByStand.values().stream()
                .sorted(Comparator.comparing(GapTotal::label, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(GapTotal::key))
                .toList();
        List<GapTotal> byDay = totalsByDay.entrySet().stream()
                .map(entry ->
                        new GapTotal(entry.getKey().toString(), entry.getKey().toString(), entry.getValue()))
                .toList();
        List<GapTotal> byTypologie = totalsByTypologie.entrySet().stream()
                .map(entry -> new GapTotal(
                        entry.getKey(), typologieLabels.getOrDefault(entry.getKey(), entry.getKey()), entry.getValue()))
                .sorted(Comparator.comparing(GapTotal::label, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(GapTotal::key))
                .toList();
        return new RealisedVsPlanned(
                everPublished,
                realised.nature(),
                frozenPast,
                today,
                List.copyOf(dayRows),
                List.copyOf(cells),
                byStand,
                byDay,
                byTypologie,
                event);
    }

    /** The cells of one day measured against its reference: every stand that held a seat on either side. */
    private static void addCells(DayReference day, Realised realised, List<RealisedCell> cells) {
        for (StandDay standDay : classifyDay(day, realised).values()) {
            if (!standDay.isBlank()) {
                cells.add(new RealisedCell(standDay.standId, standDay.standNom, day.date(), standDay.counts()));
            }
        }
    }

    /** Adds a cell's counters to every game category its stand offers. */
    private static void addToTypologies(Map<String, GapCounts> totalsByTypologie, Stand stand, GapCounts counts) {
        if (stand == null || stand.getTypologiesProposees() == null) {
            return;
        }
        for (String typologie : stand.getTypologiesProposees()) {
            totalsByTypologie.merge(typologie, counts, GapCounts::add);
        }
    }

    /** One cell opened: its counters and its lines, holders named. */
    public static CellDetail detail(DayReference day, String standId, String standNom, Realised realised) {
        if (day.plan() == null) {
            return new CellDetail(day.date(), standId, standNom, false, null, false, GapCounts.ZERO, List.of());
        }
        StandDay standDay = classifyDay(day, realised).get(standId);
        if (standDay == null) {
            return new CellDetail(
                    day.date(),
                    standId,
                    standNom,
                    true,
                    day.publishedAt(),
                    day.lateReference(),
                    GapCounts.ZERO,
                    List.of());
        }
        return new CellDetail(
                day.date(),
                standId,
                standDay.standNom,
                true,
                day.publishedAt(),
                day.lateReference(),
                standDay.counts(),
                List.copyOf(standDay.lines));
    }

    /* ------------------------------- One day ------------------------------- */

    /** What one stand did on one day, accumulated line by line. */
    private static final class StandDay {
        private final String standId;
        private String standNom;
        private int published;
        private int publishedMinutes;
        private int realisedMinutes;
        private int absences;
        private int replacements;
        private int empty;
        private int removed;
        private int added;
        private int lostMinutes;
        private final List<RealisedLine> lines = new ArrayList<>();

        StandDay(String standId, String standNom) {
            this.standId = standId;
            this.standNom = standNom;
        }

        /** A stand whose seats nobody held on either side: no cell to draw. */
        boolean isBlank() {
            return published == 0 && realisedMinutes == 0 && added == 0 && lines.isEmpty();
        }

        GapCounts counts() {
            return GapCounts.of(
                    published,
                    absences,
                    replacements,
                    empty,
                    removed,
                    added,
                    publishedMinutes,
                    realisedMinutes,
                    lostMinutes);
        }
    }

    /** Every stand of the day holding a seat on either side, by id, with its counters. */
    private static Map<String, StandDay> classifyDay(DayReference day, Realised realised) {
        LocalDate date = day.date();
        PlanningEvenement reference = day.plan();
        Map<String, StandDay> stands = new TreeMap<>();
        Map<String, Integer> chairsBefore = new HashMap<>();
        Map<String, Integer> chairsAfter = new HashMap<>();
        Map<String, Long> creneauOfHolder = new HashMap<>();

        for (PosteAffectation poste : postesOf(reference, date)) {
            StandDay standDay = stands.computeIfAbsent(
                    poste.getStand().getId(),
                    id -> new StandDay(id, poste.getStand().getNom()));
            chairsBefore.merge(cellKey(poste), 1, Integer::sum);
            if (poste.getAnimateur() != null) {
                standDay.published++;
                standDay.publishedMinutes += minutes(poste.heureDebutEffectif(), poste.heureFinEffectif());
                creneauOfHolder.put(
                        cellKey(poste) + "|" + poste.getAnimateur().getId(),
                        poste.getCreneau().getId());
            }
        }
        for (PosteAffectation poste : postesOf(realised.plan(), date)) {
            StandDay standDay = stands.computeIfAbsent(
                    poste.getStand().getId(),
                    id -> new StandDay(id, poste.getStand().getNom()));
            // Today's name wins: a stand renamed since the publication reads as it is called now.
            standDay.standNom = poste.getStand().getNom();
            chairsAfter.merge(cellKey(poste), 1, Integer::sum);
            if (poste.getAnimateur() != null) {
                standDay.realisedMinutes += minutes(poste.heureDebutEffectif(), poste.heureFinEffectif());
            }
        }

        Map<String, List<SeatLine>> linesByStand = new LinkedHashMap<>();
        for (SeatLine line : ChangementsJourneeService.compareSeats(date, reference, realised.plan())) {
            linesByStand
                    .computeIfAbsent(line.standId(), unused -> new ArrayList<>())
                    .add(line);
        }
        AbsenceReader absences = new AbsenceReader(date, creneauOfHolder, realised);
        for (Map.Entry<String, List<SeatLine>> entry : linesByStand.entrySet()) {
            StandDay standDay = stands.computeIfAbsent(
                    entry.getKey(),
                    id -> new StandDay(id, entry.getValue().getFirst().standNom()));
            classifyLines(standDay, entry.getValue(), date, chairsLost(chairsBefore, chairsAfter), absences);
        }
        return stands;
    }

    /**
     * Who was recorded missing, read on the published seat of a line.
     *
     * @param creneauOfHolder the timeslot of each published seat, by cell and holder
     */
    private record AbsenceReader(LocalDate date, Map<String, Long> creneauOfHolder, Realised realised) {

        /** Whether the published holder of this line was recorded missing on the timeslot of their published seat. */
        boolean isAbsent(SeatLine line, LocalTime start, LocalTime end) {
            if (line.avant() == null) {
                return false;
            }
            String holder = line.avant().animateurId();
            return realised.isAbsent(
                    holder, creneauOfHolder.get(cellKey(line.standId(), date, start, end) + "|" + holder));
        }

        /** Same, on the hours the line holds now — those of a seat that kept its hours. */
        boolean isAbsentOnItsHours(SeatLine line) {
            return isAbsent(line, line.heureDebut(), line.heureFin());
        }
    }

    /**
     * The chairs each published cell lost: what a reshaped seat, then a
     * removed one, accounts for.
     */
    private static Map<String, Integer> chairsLost(
            Map<String, Integer> chairsBefore, Map<String, Integer> chairsAfter) {
        Map<String, Integer> chairsLost = new HashMap<>();
        chairsBefore.forEach((key, before) -> {
            int lost = before - chairsAfter.getOrDefault(key, 0);
            if (lost > 0) {
                chairsLost.put(key, lost);
            }
        });
        return chairsLost;
    }

    /** Reads the compared lines of one stand into its counters and its lines. */
    private static void classifyLines(
            StandDay standDay,
            List<SeatLine> comparedLines,
            LocalDate date,
            Map<String, Integer> chairsLost,
            AbsenceReader absences) {
        List<SeatLine> lines = interpret(comparedLines, date, chairsLost);

        Set<SeatLine> refills = new HashSet<>();
        Map<String, List<SeatLine>> withdrawalsByCell = new LinkedHashMap<>();
        for (SeatLine line : lines) {
            switch (line.type()) {
                case REMPLACE -> classifyReplaced(standDay, line, absences);
                case RETIRE ->
                    withdrawalsByCell
                            .computeIfAbsent(
                                    cellKey(line.standId(), date, line.heureDebut(), line.heureFin()),
                                    unused -> new ArrayList<>())
                            .add(line);
                case HORAIRES -> classifyReshaped(standDay, line, lines, refills, absences);
                case NOUVEAU -> {
                    // Read once every HORAIRES line has had the chance to claim it as its remainder.
                }
            }
        }
        withdrawalsByCell.forEach((cell, withdrawals) ->
                classifyWithdrawals(standDay, withdrawals, chairsLost.getOrDefault(cell, 0), absences));
        classifyAdded(standDay, lines, refills);
        standDay.lines.sort(Comparator.comparing(
                        (RealisedLine line) -> line.seat().heureDebut(),
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(line -> line.seat().type()));
    }

    /** A seat held by somebody else: a replacement, and an absence too when its published holder was missing. */
    private static void classifyReplaced(StandDay standDay, SeatLine line, AbsenceReader absences) {
        boolean absent = absences.isAbsentOnItsHours(line);
        standDay.replacements++;
        if (absent) {
            standDay.absences++;
        }
        standDay.lines.add(new RealisedLine(line, RealisedOutcome.REPLACED, absent));
    }

    /**
     * A reshaped seat: hours a consigne moved for anybody present; for a
     * holder recorded missing, an absence — refilled when somebody took the
     * remainder, emptied otherwise, only the remainder lost.
     */
    private static void classifyReshaped(
            StandDay standDay, SeatLine line, List<SeatLine> lines, Set<SeatLine> refills, AbsenceReader absences) {
        if (!absences.isAbsent(line, line.heureDebutAvant(), line.heureFinAvant())) {
            standDay.lines.add(new RealisedLine(line, RealisedOutcome.HOURS_CHANGED, false));
            return;
        }
        standDay.absences++;
        SeatLine remainder = remainderTaken(line, lines, refills);
        if (remainder != null) {
            refills.add(remainder);
            standDay.replacements++;
            standDay.lines.add(new RealisedLine(line, RealisedOutcome.REPLACED, true));
        } else {
            standDay.empty++;
            standDay.lostMinutes += Math.max(
                    0,
                    minutes(line.heureDebutAvant(), line.heureFinAvant())
                            - minutes(line.heureDebut(), line.heureFin()));
            standDay.lines.add(new RealisedLine(line, RealisedOutcome.EMPTIED, true));
        }
    }

    /**
     * The seats nobody holds any more in one cell. The chairs the cell lost —
     * those no reshaped seat accounts for — are the removed seats; the others
     * were emptied. When both happen in one cell, the absent holders are read
     * on the emptied chairs: a missing person on a chair that is still there
     * is the absence, the chair taken away is the consigne.
     *
     * @param chairsLostInCell the chairs of the cell no reshape consumed
     */
    private static void classifyWithdrawals(
            StandDay standDay, List<SeatLine> cellWithdrawals, int chairsLostInCell, AbsenceReader absences) {
        List<SeatLine> withdrawals = new ArrayList<>(cellWithdrawals);
        int removedFromGrid = Math.min(withdrawals.size(), chairsLostInCell);
        int emptied = withdrawals.size() - removedFromGrid;
        withdrawals.sort(Comparator.comparing((SeatLine line) -> !absences.isAbsentOnItsHours(line)));
        for (int i = 0; i < withdrawals.size(); i++) {
            SeatLine line = withdrawals.get(i);
            if (i < emptied) {
                classifyEmptied(standDay, line, absences);
            } else {
                standDay.removed++;
                standDay.lines.add(new RealisedLine(line, RealisedOutcome.REMOVED, false));
            }
        }
    }

    /** A seat whose chair is still there and nobody holds: emptied, and an absence when its holder was missing. */
    private static void classifyEmptied(StandDay standDay, SeatLine line, AbsenceReader absences) {
        boolean absent = absences.isAbsentOnItsHours(line);
        standDay.empty++;
        standDay.lostMinutes += minutes(line.heureDebut(), line.heureFin());
        if (absent) {
            standDay.absences++;
        }
        standDay.lines.add(new RealisedLine(line, RealisedOutcome.EMPTIED, absent));
    }

    /** The seats nobody had been announced on: a remainder some reshaped seat claimed, or an added seat. */
    private static void classifyAdded(StandDay standDay, List<SeatLine> lines, Set<SeatLine> refills) {
        for (SeatLine line : lines) {
            if (line.type() != SeatChangeType.NOUVEAU) {
                continue;
            }
            if (refills.contains(line)) {
                standDay.lines.add(new RealisedLine(line, RealisedOutcome.REFILLED, false));
            } else {
                standDay.added++;
                standDay.lines.add(new RealisedLine(line, RealisedOutcome.ADDED, false));
            }
        }
    }

    /**
     * The lines of one stand as seats rather than as cells: which ones are
     * the same seat on other hours. A seat is <b>reshaped</b> when its holder
     * holds, on the same stand, hours overlapping the published ones, and the
     * cell of the published hours lost a chair — the split of ADR 0066 cuts
     * it in two, a consigne trims it; a chair still standing at the published
     * hours means the person left it for another one.
     *
     * <ul>
     *   <li>a {@code HORAIRES} line the comparison folded stays one when it is
     *       a reshape, and is unfolded into the seat left ({@code RETIRE}) and
     *       the seat taken ({@code NOUVEAU}) when it is a move;</li>
     *   <li>a {@code RETIRE} and a {@code NOUVEAU} of the same holder that the
     *       comparison left apart — it only folds a person's one seat on each
     *       side of the stand — are folded here into a {@code HORAIRES} line
     *       when they are a reshape.</li>
     * </ul>
     *
     * <p>Each reshape consumes one chair of {@code chairsLost}, so the chairs
     * left there are the seats really taken out.</p>
     */
    private static List<SeatLine> interpret(List<SeatLine> lines, LocalDate date, Map<String, Integer> chairsLost) {
        List<SeatLine> read = new ArrayList<>();
        List<SeatLine> withdrawn = new ArrayList<>();
        List<SeatLine> taken = new ArrayList<>();
        for (SeatLine line : lines) {
            switch (line.type()) {
                case HORAIRES -> {
                    if (isReshape(line.heureDebutAvant(), line.heureFinAvant(), line, date, chairsLost)) {
                        read.add(line);
                    } else {
                        withdrawn.add(seatLeft(line));
                        taken.add(seatTaken(line));
                    }
                }
                case RETIRE -> withdrawn.add(line);
                case NOUVEAU -> taken.add(line);
                case REMPLACE -> read.add(line);
            }
        }
        for (SeatLine withdrawal : withdrawn) {
            SeatLine kept = reshapeOf(withdrawal, taken, date, chairsLost);
            if (kept == null) {
                read.add(withdrawal);
            } else {
                taken.remove(kept);
                read.add(folded(withdrawal, kept));
            }
        }
        read.addAll(taken);
        return read;
    }

    /** The published half of a {@code HORAIRES} line that is a move: the seat left. */
    private static SeatLine seatLeft(SeatLine line) {
        return new SeatLine(
                line.standId(),
                line.standNom(),
                line.date(),
                line.heureDebutAvant(),
                line.heureFinAvant(),
                null,
                null,
                line.avant(),
                null,
                SeatChangeType.RETIRE);
    }

    /** The realised half of a {@code HORAIRES} line that is a move: the seat taken. */
    private static SeatLine seatTaken(SeatLine line) {
        return new SeatLine(
                line.standId(),
                line.standNom(),
                line.date(),
                line.heureDebut(),
                line.heureFin(),
                null,
                null,
                null,
                line.apres(),
                SeatChangeType.NOUVEAU);
    }

    /**
     * The unannounced seat of the same holder that is {@code withdrawal}
     * reshaped, consuming the chair it accounts for — {@code null} when none
     * is.
     */
    private static SeatLine reshapeOf(
            SeatLine withdrawal, List<SeatLine> taken, LocalDate date, Map<String, Integer> chairsLost) {
        for (SeatLine candidate : taken) {
            if (withdrawal.avant() != null
                    && candidate.apres() != null
                    && withdrawal.avant().animateurId().equals(candidate.apres().animateurId())
                    && isReshape(withdrawal.heureDebut(), withdrawal.heureFin(), candidate, date, chairsLost)) {
                return candidate;
            }
        }
        return null;
    }

    /** A withdrawn seat and the seat its holder kept, folded into one {@code HORAIRES} line. */
    private static SeatLine folded(SeatLine withdrawal, SeatLine kept) {
        return new SeatLine(
                kept.standId(),
                kept.standNom(),
                kept.date(),
                kept.heureDebut(),
                kept.heureFin(),
                withdrawal.heureDebut(),
                withdrawal.heureFin(),
                withdrawal.avant(),
                kept.apres(),
                SeatChangeType.HORAIRES);
    }

    /**
     * Whether the seat published on {@code debutAvant}-{@code finAvant} is
     * the one {@code maintenant} holds on its own hours — overlapping, and the
     * published cell short of a chair — consuming that chair when it is.
     */
    private static boolean isReshape(
            LocalTime debutAvant,
            LocalTime finAvant,
            SeatLine maintenant,
            LocalDate date,
            Map<String, Integer> chairsLost) {
        if (!overlap(debutAvant, finAvant, maintenant.heureDebut(), maintenant.heureFin())) {
            return false;
        }
        String cle = cellKey(maintenant.standId(), date, debutAvant, finAvant);
        int perdues = chairsLost.getOrDefault(cle, 0);
        if (perdues <= 0) {
            return false;
        }
        chairsLost.put(cle, perdues - 1);
        return true;
    }

    /** Whether two windows share a minute, either of them possibly crossing midnight. */
    private static boolean overlap(LocalTime debutA, LocalTime finA, LocalTime debutB, LocalTime finB) {
        if (debutA == null || finA == null || debutB == null || finB == null) {
            return false;
        }
        return minutesAfter(debutA, debutB) < minutes(debutA, finA)
                || minutesAfter(debutB, debutA) < minutes(debutB, finB);
    }

    /** Minutes from {@code origin} to the next {@code time}, the same day or the next. */
    private static int minutesAfter(LocalTime origin, LocalTime time) {
        int minutes = (time.toSecondOfDay() - origin.toSecondOfDay()) / 60;
        return minutes < 0 ? minutes + MINUTES_PER_DAY : minutes;
    }

    /**
     * The remainder of a seat split on the day, taken by somebody: the
     * unannounced seat of the same stand that starts where the kept part ends
     * and ends where the published seat ended.
     */
    private static SeatLine remainderTaken(SeatLine horaires, List<SeatLine> lignes, Set<SeatLine> dejaPris) {
        for (SeatLine ligne : lignes) {
            if (ligne.type() == SeatChangeType.NOUVEAU
                    && !dejaPris.contains(ligne)
                    && ligne.heureDebut() != null
                    && ligne.heureDebut().equals(horaires.heureFin())
                    && ligne.heureFin() != null
                    && ligne.heureFin().equals(horaires.heureFinAvant())) {
                return ligne;
            }
        }
        return null;
    }

    private static List<PosteAffectation> postesOf(PlanningEvenement plan, LocalDate date) {
        if (plan == null || plan.getPostes() == null) {
            return List.of();
        }
        return plan.getPostes().stream()
                .filter(poste -> poste.getStand() != null
                        && poste.getCreneau() != null
                        && date.equals(poste.getCreneau().getDate()))
                .toList();
    }

    private static void indexStands(PlanningEvenement plan, Map<String, Stand> stands) {
        if (plan == null || plan.getPostes() == null) {
            return;
        }
        for (PosteAffectation poste : plan.getPostes()) {
            if (poste.getStand() != null) {
                // The plan read last wins: the realised one carries today's referential.
                stands.put(poste.getStand().getId(), poste.getStand());
            }
        }
    }

    /** The natural key of a cell — stand, day, hours — as the comparison pairs seats on it. */
    private static String cellKey(PosteAffectation poste) {
        return cellKey(
                poste.getStand().getId(),
                poste.getCreneau().getDate(),
                poste.heureDebutEffectif(),
                poste.heureFinEffectif());
    }

    private static String cellKey(String standId, LocalDate date, LocalTime debut, LocalTime fin) {
        return standId + "|" + date + "|" + debut + "|" + fin;
    }

    /** A shift's length; one ending at or before its start crosses midnight. */
    static int minutes(LocalTime debut, LocalTime fin) {
        if (debut == null || fin == null) {
            return 0;
        }
        int minutes = (int) Duration.between(debut, fin).toMinutes();
        return minutes <= 0 ? minutes + MINUTES_PER_DAY : minutes;
    }

    /* --------------------------------- CSV --------------------------------- */

    /**
     * The grid as a CSV: one line per cell, stand and day and counters — no
     * person, by name or by id, ever. Minutes are written as hours with a
     * decimal comma, the way a French spreadsheet reads a number. The last
     * column says whether the day is counted: a day measured against a late
     * reference is exported as the screen draws it, and left out of the sums
     * the same way.
     */
    public static String csv(RealisedVsPlanned report) {
        StringBuilder csv = new StringBuilder("jour;stand;sieges publies;sieges tenus;absences;remplacements;"
                + "sieges vides;sieges retires;sieges ajoutes;heures publiees;heures realisees;heures perdues;"
                + "jour compte\n");
        Set<LocalDate> countedDays = report.days().stream()
                .filter(RealisedDay::counted)
                .map(RealisedDay::date)
                .collect(Collectors.toSet());
        for (RealisedCell cell : report.cells()) {
            GapCounts counts = cell.counts();
            csv.append(cell.date())
                    .append(';')
                    .append(escape(cell.standNom()))
                    .append(';')
                    .append(counts.publishedSeats())
                    .append(';')
                    .append(counts.keptSeats())
                    .append(';')
                    .append(counts.absences())
                    .append(';')
                    .append(counts.replacements())
                    .append(';')
                    .append(counts.emptySeats())
                    .append(';')
                    .append(counts.removedSeats())
                    .append(';')
                    .append(counts.addedSeats())
                    .append(';')
                    .append(hours(counts.publishedMinutes()))
                    .append(';')
                    .append(hours(counts.realisedMinutes()))
                    .append(';')
                    .append(hours(counts.lostMinutes()))
                    .append(';')
                    .append(countedDays.contains(cell.date()) ? "oui" : "non")
                    .append('\n');
        }
        return csv.toString();
    }

    private static String hours(int minutes) {
        return String.format(Locale.ROOT, "%.2f", minutes / 60.0).replace('.', ',');
    }

    /** A text cell, behind a quote first when a spreadsheet would run it as a formula. */
    private static String escape(String valeur) {
        if (valeur == null) {
            return "";
        }
        String cellule = CsvFormulaGuard.neutralise(valeur);
        if (cellule.contains(";") || cellule.contains("\"") || cellule.contains("\n")) {
            return "\"" + cellule.replace("\"", "\"\"") + "\"";
        }
        return cellule;
    }
}
