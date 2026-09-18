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

    /** One clock reading, so the date and the time of day cannot straddle midnight. */
    public static PastHorizon of(LocalDateTime moment) {
        return new PastHorizon(moment.toLocalDate(), moment.toLocalTime().withNano(0));
    }
}
