package dev.sylvain.planning.service.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.service.SplitSeatFixture;
import dev.sylvain.planning.service.publication.PublicationDiffService.Vacation;
import dev.sylvain.planning.service.solve.PlanSnapshotService.AffectationSnapshot;
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

    @Test
    void theSnapshotReadsASeatKeptByItsHolderAsTheWorkingPlanDoes() {
        PlanningEvenement plan = fixture.planKeptByItsHolder();

        Map<String, List<Vacation>> published = PlanPublieService.vacations(snapshotRows(plan), Map.of());

        assertThat(published.get("A-ADA"))
                .extracting(Vacation::debut, Vacation::fin)
                .containsExactly(tuple(LocalTime.of(9, 0), LocalTime.of(12, 0)));
        assertThat(published.get("A-ADA"))
                .extracting(Vacation::date, Vacation::debut, Vacation::fin, Vacation::standId)
                .containsExactlyElementsOf(PublicationDiffService.vacationsByAnimateur(plan).get("A-ADA").stream()
                        .map(vacation -> tuple(vacation.date(), vacation.debut(), vacation.fin(), vacation.standId()))
                        .toList());
    }

    @Test
    void theSnapshotKeepsTwoVacationsWhenTheRestWentToSomebodyElse() {
        Map<String, List<Vacation>> published = PlanPublieService.vacations(snapshotRows(fixture.plan()), Map.of());

        assertThat(published.get("A-ADA"))
                .extracting(Vacation::debut, Vacation::fin)
                .containsExactly(tuple(LocalTime.of(9, 0), LocalTime.of(9, 20)));
        assertThat(published.get("A-BOB"))
                .extracting(Vacation::debut, Vacation::fin)
                .containsExactly(tuple(LocalTime.of(9, 20), LocalTime.of(12, 0)));
    }

    /** The rows a capture of {@code plan} stores, as {@code PlanSnapshotService} writes them. */
    private static List<AffectationSnapshot> snapshotRows(PlanningEvenement plan) {
        return plan.getPostes().stream()
                .map(poste -> new AffectationSnapshot(
                        poste.getId(),
                        poste.getStand().getId(),
                        String.valueOf(poste.getCreneau().getId()),
                        poste.getCreneau().getDate().toString(),
                        poste.getCreneau().getHeureDebut().toString(),
                        poste.getCreneau().getHeureFin().toString(),
                        poste.getAnimateur() == null
                                ? null
                                : poste.getAnimateur().getId(),
                        text(poste.getHeureDebutEffective()),
                        text(poste.getHeureFinEffective()),
                        poste.getSuiteDe()))
                .toList();
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }
}
