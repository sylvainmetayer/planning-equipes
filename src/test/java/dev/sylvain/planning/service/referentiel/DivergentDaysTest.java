package dev.sylvain.planning.service.referentiel;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DivergentDaysTest {

    private static final LocalDate UN = LocalDate.of(2026, 7, 10);
    private static final LocalDate DEUX = LocalDate.of(2026, 7, 11);
    private static final LocalDate HORS = LocalDate.of(2026, 9, 1);

    @Test
    void countsTheDaysSomeMembersAreOffAndOthersNot() {
        assertThat(DivergentDays.of(List.of(Set.of(UN, DEUX), Set.of(DEUX)), Set.of(UN, DEUX)))
                .containsExactly(UN);
    }

    @Test
    void ignoresADayWithoutTimeslotOnceTheGridExists() {
        assertThat(DivergentDays.of(List.of(Set.of(HORS), Set.of()), Set.of(UN)))
                .isEmpty();
        assertThat(DivergentDays.of(List.of(Set.of(HORS), Set.of()), Set.of())).containsExactly(HORS);
    }
}
