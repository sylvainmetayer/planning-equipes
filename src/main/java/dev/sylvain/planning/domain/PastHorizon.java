package dev.sylvain.planning.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * The moment « le passé est figé » is judged against (ADR 0044): today, and
 * the time of day on today. Read <b>once</b> per problem — a problem carries
 * the horizon it was built under ({@link PlanningEvenement#getPastHorizon()}),
 * and every later preparation of that problem reads the same one, so a seat
 * cannot cross its start between the build and the solve and end up pinned
 * without having been re-seeded.
 *
 * @param today the date, never {@code null}
 * @param now   the time of day on that date, never {@code null}
 */
public record PastHorizon(LocalDate today, LocalTime now) {

    public PastHorizon {
        if (today == null || now == null) {
            throw new IllegalArgumentException("A horizon needs both a date and a time of day");
        }
    }

    /**
     * Whether something starting on {@code date} at {@code start} had started
     * at this horizon: a strictly earlier date, or this date with a start at
     * or before the time of day. The one definition of « déjà commencé »
     * (ADR 0044) — the freeze of a seat reads it on the seat's effective
     * start, and every screen that says a day is frozen must read it the same
     * way. A {@code null} date, or a {@code null} start on today, has not.
     */
    public boolean hasStarted(LocalDate date, LocalTime start) {
        if (date == null || date.isAfter(today)) {
            return false;
        }
        if (date.isBefore(today)) {
            return true;
        }
        return start != null && !start.isAfter(now);
    }

    /** One clock reading, so the date and the time of day cannot straddle midnight. */
    public static PastHorizon of(LocalDateTime moment) {
        return new PastHorizon(moment.toLocalDate(), moment.toLocalTime().withNano(0));
    }
}
