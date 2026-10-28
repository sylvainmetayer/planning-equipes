package dev.sylvain.planning.solver;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The seats {@code pasDeChevauchementHoraire} counts: one animateur on two seats at the same time. */
class DoubleBookedPosteFilterTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 8);
    private static final Stand STAND = new Stand("S", "S", Set.of("JEUX"), 1, 1, false);

    private final Animateur alice = new Animateur("A", "P", "N", LocalDate.of(1990, 1, 1), false);
    private final Animateur bob = new Animateur("B", "P", "N", LocalDate.of(1990, 1, 1), false);

    @Test
    void bothHalvesOfADoubleBookingAreFoundAndNothingElse() {
        PosteAffectation morning = seat("1", alice, slot(1, DAY, 10, 13));
        PosteAffectation overlapping = seat("2", alice, slot(2, DAY, 12, 15));
        PosteAffectation evening = seat("3", alice, slot(3, DAY, 18, 20));
        PosteAffectation other = seat("4", bob, slot(4, DAY, 10, 13));

        assertThat(DoubleBookedPosteFilter.scan(List.of(morning, overlapping, evening, other)))
                .containsExactlyInAnyOrder(morning, overlapping);
    }

    @Test
    void touchingSeatsAndEmptySeatsAreNotDoubleBookings() {
        PosteAffectation before = seat("1", alice, slot(1, DAY, 10, 12));
        PosteAffectation after = seat("2", alice, slot(2, DAY, 12, 14));
        PosteAffectation empty = seat("3", null, slot(3, DAY, 10, 12));

        assertThat(DoubleBookedPosteFilter.scan(List.of(before, after, empty))).isEmpty();
    }

    /** A late slot runs into the next morning: the walk looks one day ahead. */
    @Test
    void aSlotCrossingMidnightCollidesWithTheNextMorning() {
        PosteAffectation night = seat("1", alice, slot(1, DAY, 22, 2));
        PosteAffectation dawn = seat("2", alice, slot(2, DAY.plusDays(1), 1, 4));

        assertThat(DoubleBookedPosteFilter.scan(List.of(dawn, night))).containsExactlyInAnyOrder(night, dawn);
    }

    /** Overlapping timeslots, seats narrowed apart by their stand's hours: what the constraint does not count. */
    @Test
    void seatsNarrowedApartAreNotADoubleBooking() {
        PosteAffectation morning = seat("1", alice, slot(1, DAY, 10, 14));
        morning.setHeureFinEffective(LocalTime.of(12, 0));
        PosteAffectation afternoon = seat("2", alice, slot(2, DAY, 12, 16));

        assertThat(DoubleBookedPosteFilter.scan(List.of(morning, afternoon))).isEmpty();
    }

    private static PosteAffectation seat(String id, Animateur animateur, Creneau creneau) {
        PosteAffectation poste = new PosteAffectation(id, STAND, creneau);
        poste.setAnimateur(animateur);
        return poste;
    }

    private static Creneau slot(long id, LocalDate date, int start, int end) {
        return new Creneau(id, (int) id, date, LocalTime.of(start, 0), LocalTime.of(end, 0));
    }
}
