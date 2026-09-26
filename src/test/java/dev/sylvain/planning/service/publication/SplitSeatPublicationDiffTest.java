package dev.sylvain.planning.service.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import dev.sylvain.planning.service.SplitSeatFixture;
import dev.sylvain.planning.service.publication.PublicationDiffService.Vacation;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What a publication tells the people of a timeslot repaired on the day (ADR
 * 0066, {@link SplitSeatFixture}): the one who left and the one who came, and
 * nobody else — and nothing at all to someone who kept both parts of a seat.
 */
class SplitSeatPublicationDiffTest {

    private final SplitSeatFixture fixture = new SplitSeatFixture();
    private final PublicationDiffService diff = new PublicationDiffService();

    @Test
    void onlyTheOneWhoLeftAndTheOneWhoCameHaveAChange() {
        assertThat(diff.changedPeople(fixture.plan(), fixture.publishedBefore()))
                .containsExactlyInAnyOrder("A-ADA", "A-BOB");
    }

    @Test
    void theNewHolderIsToldOfTheRestOfTheTimeslotOnly() {
        List<Vacation> bob =
                PublicationDiffService.vacationsByAnimateur(fixture.plan()).get("A-BOB");

        assertThat(bob)
                .extracting(Vacation::debut, Vacation::fin)
                .containsExactly(tuple(LocalTime.of(9, 20), LocalTime.of(12, 0)));
    }

    @Test
    void bothPartsKeptByOneHolderAreTheSeatTheyWerePublished() {
        Map<String, List<Vacation>> current =
                PublicationDiffService.vacationsByAnimateur(fixture.planKeptByItsHolder());

        assertThat(current.get("A-ADA"))
                .extracting(Vacation::debut, Vacation::fin)
                .containsExactly(tuple(LocalTime.of(9, 0), LocalTime.of(12, 0)));
        assertThat(diff.changedPeople(fixture.planKeptByItsHolder(), fixture.publishedBefore()))
                .isEmpty();
    }
}
