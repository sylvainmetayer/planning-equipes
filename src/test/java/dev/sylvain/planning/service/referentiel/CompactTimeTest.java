package dev.sylvain.planning.service.referentiel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sylvain.planning.service.BusinessError;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;

/** The hour dialects a person and a spreadsheet write, read as one rule. */
class CompactTimeTest {

    @Test
    void readsEveryFormAHumanOrASpreadsheetWrites() {
        assertThat(CompactTime.parse("09:00")).isEqualTo(LocalTime.of(9, 0));
        assertThat(CompactTime.parse("9:00")).isEqualTo(LocalTime.of(9, 0));
        assertThat(CompactTime.parse("9h")).isEqualTo(LocalTime.of(9, 0));
        assertThat(CompactTime.parse("9h30")).isEqualTo(LocalTime.of(9, 30));
        assertThat(CompactTime.parse("9H30")).isEqualTo(LocalTime.of(9, 30));
        assertThat(CompactTime.parse("9")).isEqualTo(LocalTime.of(9, 0));
        assertThat(CompactTime.parse(" 20 ")).isEqualTo(LocalTime.of(20, 0));
        // What a spreadsheet writes back when it retypes a time column.
        assertThat(CompactTime.parse("09:00:00")).isEqualTo(LocalTime.of(9, 0));
        assertThat(CompactTime.parse("9:05:00")).isEqualTo(LocalTime.of(9, 5));
        // Midnight, which a night timeslot ends on.
        assertThat(CompactTime.parse("00:00")).isEqualTo(LocalTime.MIDNIGHT);
    }

    @Test
    void refusesWhatIsNotAnHourAtAll() {
        for (String texte : new String[] {"", "  ", "midi", "25:00", "9:75", "09-00", null}) {
            assertThatThrownBy(() -> CompactTime.parse(texte))
                    .isInstanceOf(BusinessError.Invalid.class)
                    .hasMessageContaining("HH:MM");
        }
    }

    /** The compact vacation line reads its hours here, so the two can never drift apart. */
    @Test
    void theCompactVacationLineReadsTheSameForms() {
        assertThat(VacationsLigne.format(VacationsLigne.parse("9h-12h, 12h-13h R, 13-20")))
                .isEqualTo("09:00-12:00, 12:00-13:00 R, 13:00-20:00");
    }
}
