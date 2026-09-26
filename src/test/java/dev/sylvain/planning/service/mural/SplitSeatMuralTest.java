package dev.sylvain.planning.service.mural;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.service.SplitSeatFixture;
import dev.sylvain.planning.service.mural.AffichageMuralView.MuralShift;
import dev.sylvain.planning.service.mural.AffichageMuralView.MuralStand;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The wall display of a timeslot repaired on the day (ADR 0066,
 * {@link SplitSeatFixture}): each part is a shift on its own window, the
 * place to fill is one empty seat — the hole the published plan already had,
 * narrowed, never a new one —, and the rest of a seat its holder left empty
 * is new.
 */
class SplitSeatMuralTest {

    private final SplitSeatFixture fixture = new SplitSeatFixture();

    @Test
    void eachPartIsAShiftOnItsWindowAndTheNarrowedSeatIsTheKnownHole() {
        AffichageMuralView view = view(fixture.plan());

        MuralStand circus = view.stands().stream()
                .filter(stand -> stand.standId().equals("CIRQUE"))
                .findFirst()
                .orElseThrow();
        assertThat(circus.vacations())
                .extracting(
                        shift -> shift.start().toLocalTime(),
                        shift -> shift.end().toLocalTime(),
                        MuralShift::noms)
                .containsExactly(
                        tuple(LocalTime.of(9, 0), LocalTime.of(9, 20), List.of("Ada Lovelace")),
                        tuple(LocalTime.of(9, 0), LocalTime.of(12, 0), List.of("Cyd Charisse")),
                        tuple(LocalTime.of(9, 20), LocalTime.of(12, 0), List.of("Bob Kahn")));
        assertThat(view.stands().stream()
                        .flatMap(stand -> stand.vacations().stream())
                        .mapToInt(MuralShift::emptySeats)
                        .sum())
                .isEqualTo(1);
        assertThat(view.stands().stream()
                        .flatMap(stand -> stand.vacations().stream())
                        .mapToInt(MuralShift::newEmptySeats)
                        .sum())
                .isZero();
    }

    @Test
    void theRestOfASeatItsHolderLeftEmptyIsANewHole() {
        fixture.continuation.setAnimateur(null);

        assertThat(AffichageMuralViewBuilder.newHoles(
                        fixture.plan().getPostes(), fixture.publishedBefore().getPostes()))
                .containsExactly("p1~0920");
    }

    private AffichageMuralView view(PlanningEvenement plan) {
        return AffichageMuralViewBuilder.build(
                new AffichageMuralViewBuilder.Inputs(
                        "E",
                        "Édition",
                        LocalDateTime.of(SplitSeatFixture.SATURDAY, LocalTime.of(10, 0)),
                        SplitSeatFixture.SATURDAY,
                        List.of(fixture.morning),
                        plan.getPostes(),
                        null,
                        null,
                        fixture.publishedBefore().getPostes(),
                        0),
                AffichageMuralViewBuilder.Settings.wholeEdition(true));
    }
}
