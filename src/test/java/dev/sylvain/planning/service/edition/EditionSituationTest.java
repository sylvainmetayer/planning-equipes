package dev.sylvain.planning.service.edition;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.Edition;
import dev.sylvain.planning.service.edition.EditionActivationService.SituationKind;
import dev.sylvain.planning.service.referentiel.CreneauRepository.DateBounds;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** What an edition's bounds ask of the organiser on a given day (ADR 0072). */
class EditionSituationTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 7, 10);

    private static Edition edition(boolean active) {
        return new Edition("E2", "Année 2026", active, null);
    }

    private static DateBounds days(int fromToday, int toToday) {
        return new DateBounds(TODAY.plusDays(fromToday), TODAY.plusDays(toToday));
    }

    @Test
    void anActiveEditionIsFlaggedOnlyOnceItsLastDayIsOver() {
        assertThat(EditionActivationService.situationOf(edition(true), days(-5, -1), TODAY, true, 7))
                .contains(SituationKind.ACTIVE_TERMINEE);
        assertThat(EditionActivationService.situationOf(edition(true), days(-5, 0), TODAY, true, 7))
                .isEmpty();
    }

    @Test
    void anInactiveEditionStartingWithinTheWindowIsFlagged() {
        assertThat(EditionActivationService.situationOf(edition(false), days(3, 6), TODAY, true, 7))
                .contains(SituationKind.INACTIVE_IMMINENTE);
        assertThat(EditionActivationService.situationOf(edition(false), days(8, 9), TODAY, true, 7))
                .isEmpty();
        assertThat(EditionActivationService.situationOf(edition(false), days(3, 6), TODAY, false, 7))
                .contains(SituationKind.AUCUNE_ACTIVE);
    }

    /** Already started and still inactive: its espace is closed, which is the most urgent case. */
    @Test
    void anInactiveEditionAlreadyUnderWayIsFlagged() {
        assertThat(EditionActivationService.situationOf(edition(false), days(-1, 2), TODAY, true, 7))
                .contains(SituationKind.INACTIVE_EN_COURS);
        assertThat(EditionActivationService.situationOf(edition(false), days(-1, 2), TODAY, false, 7))
                .contains(SituationKind.AUCUNE_ACTIVE);
        assertThat(EditionActivationService.situationOf(edition(false), days(-5, -1), TODAY, true, 7))
                .as("an edition that is over asks nothing")
                .isEmpty();
    }
}
