package dev.sylvain.planning.service.referentiel;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The quote that keeps a spreadsheet from running a cell, put on and taken off. No container. */
class CsvFormulaGuardTest {

    @ParameterizedTest
    @ValueSource(strings = {"=1+1", "+33 6 12 34 56 78", "-foo", "@SUM(A1:A2)", "\tindent", "\rretour", "-", "-3x"})
    void quotesWhatASpreadsheetWouldRunAndRestoresIt(String formula) {
        String guarded = CsvFormulaGuard.neutralise(formula);

        assertThat(guarded).isEqualTo("'" + formula);
        assertThat(CsvFormulaGuard.restore(guarded)).isEqualTo(formula);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-3", "-1.5", "-0.24", "-1,5", "-1.0E-4", "12", "Stand deux", "", "a=b", "'déjà cité"})
    void leavesNumbersAndPlainTextAlone(String value) {
        assertThat(CsvFormulaGuard.neutralise(value)).isEqualTo(value);
        assertThat(CsvFormulaGuard.restore(value)).isEqualTo(value);
    }

    @Test
    void passesNullThrough() {
        assertThat(CsvFormulaGuard.neutralise(null)).isNull();
        assertThat(CsvFormulaGuard.restore(null)).isNull();
    }

    /** Only the quote the export itself would have written comes off, and only one of them. */
    @Test
    void restoresASingleQuoteBeforeAFormulaStartOnly() {
        assertThat(CsvFormulaGuard.restore("''=1")).isEqualTo("''=1");
        assertThat(CsvFormulaGuard.restore("'texte")).isEqualTo("'texte");
        assertThat(CsvFormulaGuard.restore("'-3")).isEqualTo("-3");
    }
}
