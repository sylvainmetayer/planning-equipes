package dev.sylvain.planning.service.referentiel;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The date dialects the CSV import reads, and above all the pivot a two-digit
 * year resolves on — pinned to a fixed « today », since the rule is stated
 * relative to it and a test reading the clock would change meaning every
 * New Year's Eve.
 */
class AnimateurCsvDateTest {

    /** The day the examples of the documentation are written against. */
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 11);

    @ParameterizedTest
    @CsvSource({
        "2000-01-01, 2000-01-01",
        "1/1/2000, 2000-01-01",
        "01-01-2000, 2000-01-01",
        "1.1.2000, 2000-01-01",
        "12/03/1990, 1990-03-12",
    })
    void fourDigitYearsReadAsBefore(String cell, LocalDate expected) {
        assertThat(AnimateurCsvImportService.parseDate(cell, TODAY)).isEqualTo(expected);
        assertThat(AnimateurCsvImportService.hasShortYear(cell)).isFalse();
    }

    /**
     * The nearest past year, the current one included: {@code 26} is 2026
     * even for a day still to come, {@code 27} is a century ago. The caller's
     * plausibility check is what refuses the first; the parser does not.
     */
    @ParameterizedTest
    @CsvSource({
        "01-01-00, 2000-01-01",
        "5/3/95, 1995-03-05",
        "12.09.26, 2026-09-12",
        "12/09/27, 1927-09-12",
        "11/09/26, 2026-09-11",
        "1/1/26, 2026-01-01",
    })
    void twoDigitYearsResolveToTheNearestPastYear(String cell, LocalDate expected) {
        assertThat(AnimateurCsvImportService.parseDate(cell, TODAY)).isEqualTo(expected);
        assertThat(AnimateurCsvImportService.hasShortYear(cell)).isTrue();
    }

    /** The ceiling is the caller's: under an event ending in 2030, {@code 30} is 2030, {@code 31} is 1931. */
    @Test
    void ceilingDecidesTheCentury() {
        LocalDate lastEventDay = LocalDate.of(2030, 7, 19);

        assertThat(AnimateurCsvImportService.parseDate("18/07/30", lastEventDay))
                .isEqualTo(LocalDate.of(2030, 7, 18));
        assertThat(AnimateurCsvImportService.parseDate("18/07/31", lastEventDay))
                .isEqualTo(LocalDate.of(1931, 7, 18));
    }

    /** Two digits do not loosen the calendar: an impossible day is still not a date, in any dialect. */
    @ParameterizedTest
    @CsvSource({"31/02/26", "31-02-2026", "32/13/1990", "0/1/26", "1/13/26", "pas-une-date", "2026/09/11", "260911"})
    void strictInEveryDialect(String cell) {
        assertThat(AnimateurCsvImportService.parseDate(cell, TODAY)).isNull();
    }

    @Test
    void blankReadsAsNoDate() {
        assertThat(AnimateurCsvImportService.parseDate(null, TODAY)).isNull();
        assertThat(AnimateurCsvImportService.parseDate("   ", TODAY)).isNull();
    }
}
