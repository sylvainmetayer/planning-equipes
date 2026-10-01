package dev.sylvain.planning.service.analyse;

import dev.sylvain.planning.service.analyse.RealisedSource.RealisedNature;
import dev.sylvain.planning.service.journee.ChangementsJournee.SeatLine;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Réalisé vs planifié: the gap between the plan the animateurs were sent and
 * the plan that was held, stand by stand and day by day, over the elapsed days
 * of the event.
 *
 * <p>Nothing here names a person. The grid, the totals and the CSV are counts;
 * a name is only read in {@link CellDetail}, on screen, by the admin.</p>
 *
 * @param referenceAvailable false while nothing was ever published: there is
 *                           no promise to measure the realised against, and
 *                           the screen says so instead of a grid of zeros
 * @param nature             how the realised was obtained — declared in the
 *                           application today
 * @param frozenPast         false when the past rule is switched off
 *                           ({@code PASSE_FIGE=false}): a solve may then have
 *                           rewritten an elapsed day, and the realised is no
 *                           longer guaranteed
 * @param today              the date of the moment the elapsed days are
 *                           judged at
 * @param days               every elapsed day of the event — its last
 *                           timeslot over —, counted or not
 * @param cells              one per stand and day with a reference holding
 *                           a seat on either side — those of a day measured
 *                           against a late reference included, drawn but
 *                           not summed
 * @param byStand            the cells of the counted days summed per stand
 * @param byDay              the cells summed per counted day
 * @param byTypologie        the cells of the counted days summed per game
 *                           category: a stand offering several counts under
 *                           each, so these do not add up to {@code event}
 * @param event              every cell of the counted days summed
 */
@Schema(
        requiredProperties = {
            "byDay",
            "byStand",
            "byTypologie",
            "cells",
            "days",
            "event",
            "frozenPast",
            "nature",
            "referenceAvailable",
            "today"
        })
public record RealisedVsPlanned(
        boolean referenceAvailable,
        RealisedNature nature,
        boolean frozenPast,
        LocalDate today,
        List<RealisedDay> days,
        List<RealisedCell> cells,
        List<GapTotal> byStand,
        List<GapTotal> byDay,
        List<GapTotal> byTypologie,
        GapCounts event) {

    /**
     * One elapsed day — its last timeslot over — and the publication it is
     * measured against.
     *
     * @param counted              true when a publication was in force when
     *                             the day started — at its first timeslot —,
     *                             the only days the totals, the CSV's counted
     *                             column and the frozen measure sum
     * @param referencePublishedAt when that publication was made
     * @param lateReference        true when nothing had been published before
     *                             the day started, so the first publication
     *                             made after its start stands in. That
     *                             publication already holds the changes made
     *                             before it, which would read as held: the day
     *                             is drawn, flagged, and never counted
     */
    @Schema(requiredProperties = {"counted", "date", "lateReference"})
    public record RealisedDay(LocalDate date, boolean counted, Instant referencePublishedAt, boolean lateReference) {}

    /** The gap of one stand on one day. */
    @Schema(requiredProperties = {"counts", "date", "standId", "standNom"})
    public record RealisedCell(String standId, String standNom, LocalDate date, GapCounts counts) {}

    /**
     * A total over several cells.
     *
     * @param key   the stand id, the date, or the game category id
     * @param label what the screen prints for it
     */
    @Schema(requiredProperties = {"counts", "key", "label"})
    public record GapTotal(String key, String label, GapCounts counts) {}

    /**
     * The counters of a cell or of a total.
     *
     * @param publishedSeats   seats the reference publication gave somebody
     * @param keptSeats        of those, the ones still held by somebody —
     *                         their holder or a replacement
     * @param absences         published holders recorded missing on their seat
     * @param replacements     published seats held by somebody else in the
     *                         end, for an absence or any other reason
     * @param emptySeats       published seats nobody held in the end — a seat
     *                         split on the day whose remainder nobody took
     *                         included: the part its holder held counts in
     *                         {@code realisedMinutes}, the remainder in
     *                         {@code lostMinutes}
     * @param removedSeats     published seats that no longer exist — taken
     *                         out by a consigne, or their timeslot deleted:
     *                         never an absence
     * @param addedSeats       seats held that the publication did not announce
     * @param publishedMinutes the minutes the publication announced
     * @param realisedMinutes  the minutes held in the end
     * @param lostMinutes      the published minutes nobody held: the empty
     *                         seats, never the removed ones
     * @param absenceRate      {@code absences / publishedSeats}, {@code null}
     *                         without a published seat
     * @param replacementRate  {@code replacements / publishedSeats}, {@code
     *                         null} without a published seat
     */
    @Schema(
            requiredProperties = {
                "absences",
                "addedSeats",
                "emptySeats",
                "keptSeats",
                "lostMinutes",
                "publishedMinutes",
                "publishedSeats",
                "realisedMinutes",
                "removedSeats",
                "replacements"
            })
    public record GapCounts(
            int publishedSeats,
            int keptSeats,
            int absences,
            int replacements,
            int emptySeats,
            int removedSeats,
            int addedSeats,
            int publishedMinutes,
            int realisedMinutes,
            int lostMinutes,
            Double absenceRate,
            Double replacementRate) {

        public static final GapCounts ZERO = of(0, 0, 0, 0, 0, 0, 0, 0, 0);

        /** The counters, the kept seats and the rates derived from them. */
        public static GapCounts of(
                int publishedSeats,
                int absences,
                int replacements,
                int emptySeats,
                int removedSeats,
                int addedSeats,
                int publishedMinutes,
                int realisedMinutes,
                int lostMinutes) {
            return new GapCounts(
                    publishedSeats,
                    Math.max(0, publishedSeats - emptySeats - removedSeats),
                    absences,
                    replacements,
                    emptySeats,
                    removedSeats,
                    addedSeats,
                    publishedMinutes,
                    realisedMinutes,
                    lostMinutes,
                    rate(absences, publishedSeats),
                    rate(replacements, publishedSeats));
        }

        /** The two counters added up, rates derived again rather than averaged. */
        public GapCounts add(GapCounts other) {
            return of(
                    publishedSeats + other.publishedSeats,
                    absences + other.absences,
                    replacements + other.replacements,
                    emptySeats + other.emptySeats,
                    removedSeats + other.removedSeats,
                    addedSeats + other.addedSeats,
                    publishedMinutes + other.publishedMinutes,
                    realisedMinutes + other.realisedMinutes,
                    lostMinutes + other.lostMinutes);
        }

        private static Double rate(int count, int total) {
            return total == 0 ? null : (double) count / total;
        }
    }

    /**
     * One cell opened: its counters and the shifts behind them, holders
     * named — the Changements rendering of the Journée page, kept to the stand.
     *
     * @param referenceAvailable false when the day has no reference, or is not
     *                           elapsed: nothing to compare, {@code lines}
     *                           empty
     */
    @Schema(
            requiredProperties = {
                "counts",
                "date",
                "lateReference",
                "lines",
                "referenceAvailable",
                "standId",
                "standNom"
            })
    public record CellDetail(
            LocalDate date,
            String standId,
            String standNom,
            boolean referenceAvailable,
            Instant referencePublishedAt,
            boolean lateReference,
            GapCounts counts,
            List<RealisedLine> lines) {}

    /**
     * One shift of the cell whose holder or hours moved since the
     * publication, and what the measure made of it.
     *
     * @param absence true when its published holder was recorded missing on it
     */
    @Schema(requiredProperties = {"absence", "outcome", "seat"})
    public record RealisedLine(SeatLine seat, RealisedOutcome outcome, boolean absence) {}

    /** What became of a published seat — or where an unannounced one came from. */
    public enum RealisedOutcome {

        /** Held by somebody else than its published holder. */
        REPLACED,

        /** Held by nobody in the end — in full, or the remainder of a seat split on the day. */
        EMPTIED,

        /** No longer exists: a consigne took it out, or its timeslot was deleted. */
        REMOVED,

        /** Held by its published holder on other hours, for another reason than an absence. */
        HOURS_CHANGED,

        /** The remainder of a split seat, taken by a replacement. */
        REFILLED,

        /** Held, and not announced by the publication. */
        ADDED
    }

    /**
     * The measure of a finished edition, as the next one reads it — on the
     * Versions page and, for information, on the Besoin tab.
     *
     * @param available   false when no earlier edition left a measure
     * @param editionId   the edition measured; it may have been deleted since
     * @param editionNom  its name when the measure was frozen
     * @param firstDay    the first day of its event
     * @param lastDay     the last day of its event
     * @param countedDays how many of its days had a publication in force when
     *                    they started
     * @param frozenAt    when the nightly job wrote the measure — once, the
     *                    first night after the event ended
     * @param event       the whole event
     * @param byTypologie per game category, under the ids and names of that
     *                    edition — the next one matches them by name, an id
     *                    being a per-edition counter that may name another
     *                    category there
     */
    @Schema(requiredProperties = {"available", "byTypologie"})
    public record PreviousEdition(
            boolean available,
            String editionId,
            String editionNom,
            LocalDate firstDay,
            LocalDate lastDay,
            Integer countedDays,
            Instant frozenAt,
            GapCounts event,
            List<GapTotal> byTypologie) {

        public static final PreviousEdition NONE =
                new PreviousEdition(false, null, null, null, null, null, null, null, List.of());
    }
}
