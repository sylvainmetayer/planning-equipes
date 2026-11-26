package dev.sylvain.planning.service.referentiel;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sylvain.planning.domain.PlageStand;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.BusinessError;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** What the write refuses among a stand's plages: an inverted window, a day in both modes, a headcount past the maximum. */
class StandValidatorPlagesTest {

    private static final LocalDate JOUR = LocalDate.of(2026, 7, 8);

    private static Stand stand(PlageStand... plages) {
        Stand stand = new Stand("S", "S", Set.of("T"), 1, 5, false);
        for (PlageStand plage : plages) {
            stand.getPlages().add(plage);
        }
        return stand;
    }

    private static PlageStand ouverture(int debut, int fin, Integer effectif) {
        return PlageStand.ouverture(null, JOUR, LocalTime.of(debut, 0), LocalTime.of(fin, 0), null, effectif);
    }

    @Test
    void aDayCannotCarryBothAnOpeningAndAClosure() {
        Stand stand = stand(
                ouverture(10, 18, null),
                PlageStand.fermeture(null, JOUR, LocalTime.of(12, 0), LocalTime.of(14, 0), null));

        assertThatThrownBy(() -> StandValidator.checkSchedule(stand)).isInstanceOf(BusinessError.Invalid.class);
    }

    @Test
    void overlappingOpeningsOfOneDayAreWrittenAndOnlyReported() {
        Stand stand = stand(ouverture(14, 20, 4), ouverture(14, 20, 2));

        assertThatCode(() -> StandValidator.checkSchedule(stand)).doesNotThrowAnyException();
    }

    @Test
    void aWindowEndingBeforeItsStartIsRefused() {
        Stand stand = stand(ouverture(18, 10, null));

        assertThatThrownBy(() -> StandValidator.checkSchedule(stand)).isInstanceOf(BusinessError.Invalid.class);
    }

    @Test
    void anOpenEndedWindowIsWritten() {
        Stand stand = stand(PlageStand.ouverture(null, JOUR, LocalTime.of(14, 0), null, null));

        assertThatCode(() -> StandValidator.checkSchedule(stand)).doesNotThrowAnyException();
    }

    @Test
    void aHeadcountAboveTheStandMaximumIsRefused() {
        Stand stand = stand(ouverture(10, 12, 9));

        assertThatThrownBy(() -> StandValidator.checkSchedule(stand)).isInstanceOf(BusinessError.Invalid.class);
    }
}
