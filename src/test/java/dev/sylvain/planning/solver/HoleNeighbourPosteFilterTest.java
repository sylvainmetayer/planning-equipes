package dev.sylvain.planning.solver;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;

import org.junit.jupiter.api.Test;

import dev.sylvain.planning.domain.Creneau;

/** The neighbourhood a hole is filled from: the créneaux that share its hour, midnight included. */
class HoleNeighbourPosteFilterTest {

    private static final LocalDate JOUR = LocalDate.of(2026, 9, 8);

    @Test
    void twoSlotsOfTheSameDaySharingMinutesOverlap() {
        assertThat(HoleNeighbourPosteFilter.overlap(slot(JOUR, 13, 45, 16, 45), slot(JOUR, 14, 15, 20, 0))).isTrue();
        assertThat(HoleNeighbourPosteFilter.overlap(slot(JOUR, 10, 0, 12, 0), slot(JOUR, 10, 0, 12, 15))).isTrue();
    }

    @Test
    void touchingOrDistinctSlotsDoNot() {
        // 12:00 ends where the other starts: nobody is on both.
        assertThat(HoleNeighbourPosteFilter.overlap(slot(JOUR, 10, 0, 12, 0), slot(JOUR, 12, 0, 13, 0))).isFalse();
        assertThat(HoleNeighbourPosteFilter.overlap(slot(JOUR, 9, 0, 12, 0), slot(JOUR.plusDays(1), 9, 0, 12, 0))).isFalse();
    }

    @Test
    void aSlotCrossingMidnightStillOverlapsTheEvening() {
        assertThat(HoleNeighbourPosteFilter.overlap(slot(JOUR, 20, 0, 0, 0), slot(JOUR, 22, 0, 23, 0))).isTrue();
    }

    private static Creneau slot(LocalDate date, int h1, int m1, int h2, int m2) {
        return new Creneau(null, 1, date, LocalTime.of(h1, m1), LocalTime.of(h2, m2));
    }
}
