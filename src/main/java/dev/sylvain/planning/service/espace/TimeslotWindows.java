package dev.sylvain.planning.service.espace;

import dev.sylvain.planning.domain.Creneau;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The wall-clock reading of timeslots that the mode jour J screen and the wall
 * display share: the window a timeslot really covers, and the journée under way
 * at a given moment. Pure and static, written once so the two screens can never
 * disagree on which day « today » is at one in the morning.
 */
public final class TimeslotWindows {

    private TimeslotWindows() {}

    /**
     * The window from {@code start} to {@code end} on {@code date}. A window
     * whose end is not after its start crosses midnight and ends the next day —
     * the same normalisation {@code Creneau.chevaucheNuit} applies.
     */
    public static LocalDateTime[] window(LocalDate date, LocalTime start, LocalTime end) {
        LocalDateTime from = date.atTime(start);
        LocalDateTime to = date.atTime(end);
        return new LocalDateTime[] {from, to.isAfter(from) ? to : to.plusDays(1)};
    }

    /** The window a timeslot really covers; see {@link #window(LocalDate, LocalTime, LocalTime)}. */
    public static LocalDateTime[] window(Creneau creneau) {
        return window(creneau.getDate(), creneau.getHeureDebut(), creneau.getHeureFin());
    }

    /** Started but not over at {@code reference}. */
    public static boolean isUnderWay(Creneau creneau, LocalDateTime reference) {
        LocalDateTime[] window = window(creneau);
        return !window[0].isAfter(reference) && window[1].isAfter(reference);
    }

    /**
     * The journée under way at {@code now}: the latest day whose first timeslot
     * has started and whose last one has not ended — the evening that opened a
     * 22:00-02:00 shift is still « today » at one in the morning. The calendar
     * date when no journée is under way. Timeslots missing a date or an hour
     * are skipped.
     */
    public static LocalDate currentDay(Collection<Creneau> creneaux, LocalDateTime now) {
        Map<LocalDate, LocalDateTime[]> spans = new LinkedHashMap<>();
        for (Creneau creneau : creneaux) {
            if (creneau.getDate() == null || creneau.getHeureDebut() == null || creneau.getHeureFin() == null) {
                continue;
            }
            spans.merge(creneau.getDate(), window(creneau), (a, b) ->
                    new LocalDateTime[] {a[0].isBefore(b[0]) ? a[0] : b[0], a[1].isAfter(b[1]) ? a[1] : b[1]});
        }
        return spans.entrySet().stream()
                .filter(span -> !span.getValue()[0].isAfter(now) && span.getValue()[1].isAfter(now))
                .map(Map.Entry::getKey)
                .max(Comparator.naturalOrder())
                .orElseGet(now::toLocalDate);
    }
}
