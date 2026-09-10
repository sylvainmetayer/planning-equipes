package dev.sylvain.planning.service.referentiel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import dev.sylvain.planning.service.BusinessError;

/**
 * The dialects a real spreadsheet export arrives in.
 *
 * <p>No container and no database: the parser is pure, and the cases it has to
 * survive — a semicolon separator, a byte-order mark, a quoted cell holding a
 * newline — are exactly the ones nobody notices until a file lands one column
 * to the left.</p>
 */
class CsvParserTest {

    @Test
    void readsACommaSeparatedFileWithItsHeader() {
        CsvParser.Table table = CsvParser.parse("prenom,nom\nAmélie,Durand\nBruno,Lefèvre\n");

        assertThat(table.separator()).isEqualTo(',');
        assertThat(table.columns()).containsExactly("prenom", "nom");
        assertThat(table.rows()).hasSize(2);
        assertThat(table.rows().get(0).values()).containsExactly("Amélie", "Durand");
    }

    /** French Excel writes semicolons; a comma inside a quoted cell must not win the vote. */
    @Test
    void recognisesTheSemicolonOfAFrenchSpreadsheet() {
        CsvParser.Table table = CsvParser.parse("prenom;nom;competences\nAmélie;Durand;\"jeux,ateliers\"\n");

        assertThat(table.separator()).isEqualTo(';');
        assertThat(table.columns()).containsExactly("prenom", "nom", "competences");
        assertThat(table.rows().get(0).values()).containsExactly("Amélie", "Durand", "jeux,ateliers");
    }

    @Test
    void stripsTheByteOrderMarkOfASaveAsUtf8Csv() {
        CsvParser.Table table = CsvParser.parse("\uFEFFprenom,nom\nAmélie,Durand\n");

        assertThat(table.columns()).containsExactly("prenom", "nom");
    }

    @Test
    void readsCrlfAsWellAsLf() {
        CsvParser.Table table = CsvParser.parse("prenom,nom\r\nAmélie,Durand\r\nBruno,Lefèvre\r\n");

        assertThat(table.rows()).hasSize(2);
        assertThat(table.rows().get(1).values()).containsExactly("Bruno", "Lefèvre");
    }

    @Test
    void unescapesADoubledQuoteInsideAQuotedCell() {
        CsvParser.Table table = CsvParser.parse("nom,note\nDurand,\"dit \"\"Mimi\"\"\"\n");

        assertThat(table.rows().get(0).values()).containsExactly("Durand", "dit \"Mimi\"");
    }

    /** A quoted cell may hold a line break, and the next row's line number must survive it. */
    @Test
    void keepsALineBreakInsideACellAndStillCountsPhysicalLines() {
        CsvParser.Table table = CsvParser.parse("nom,note\nDurand,\"deux\nlignes\"\nLefèvre,rien\n");

        assertThat(table.rows().get(0).values()).containsExactly("Durand", "deux\nlignes");
        assertThat(table.rows().get(0).line()).isEqualTo(2);
        assertThat(table.rows().get(1).line()).isEqualTo(4);
    }

    @Test
    void refusesAnEmptyFile() {
        assertThatThrownBy(() -> CsvParser.parse("   \n  "))
                .isInstanceOf(BusinessError.Invalid.class)
                .hasMessageContaining("vide");
    }

    @Test
    void acceptsAHeaderWithoutASingleDataRow() {
        CsvParser.Table table = CsvParser.parse("prenom,nom\n");

        assertThat(table.columns()).containsExactly("prenom", "nom");
        assertThat(table.rows()).isEmpty();
    }

    @Test
    void dropsBlankLinesWhereverTheyAre() {
        CsvParser.Table table = CsvParser.parse("prenom,nom\n\nAmélie,Durand\n,\n\n");

        assertThat(table.rows()).hasSize(1);
        assertThat(table.rows().get(0).line()).isEqualTo(3);
    }

    /** A row shorter than the header reads its missing cells as empty, not as an error. */
    @Test
    void padsARowShorterThanTheHeader() {
        CsvParser.Table table = CsvParser.parse("prenom,nom,email\nAmélie,Durand\n");

        assertThat(table.rows().get(0).value(2)).isEmpty();
    }

    @Test
    void keepsTheExtraCellsOfARowLongerThanTheHeader() {
        CsvParser.Table table = CsvParser.parse("prenom,nom\nAmélie,Durand,trop\n");

        assertThat(table.rows().get(0).values()).containsExactly("Amélie", "Durand", "trop");
    }

    /** Duplicate and empty headers are read as they are: the mapping is by index. */
    @Test
    void keepsDuplicateAndEmptyHeadersAsTheyAre() {
        CsvParser.Table table = CsvParser.parse("nom,,nom\nDurand,x,Durand\n");

        assertThat(table.columns()).containsExactly("nom", "", "nom");
    }

    @Test
    void trimsTheHeaderButNotTheCells() {
        CsvParser.Table table = CsvParser.parse(" prenom , nom \n  Amélie  , Durand \n");

        assertThat(table.columns()).containsExactly("prenom", "nom");
        assertThat(table.rows().get(0).values()).containsExactly("  Amélie  ", " Durand ");
    }

    @Test
    void readsATabSeparatedExport() {
        CsvParser.Table table = CsvParser.parse("prenom\tnom\nAmélie\tDurand\n");

        assertThat(table.separator()).isEqualTo('\t');
        assertThat(table.columns()).containsExactly("prenom", "nom");
    }

    /** A single-column file has no separator to detect; the comma is the fallback. */
    @Test
    void fallsBackToTheCommaWhenNoSeparatorAppears() {
        CsvParser.Table table = CsvParser.parse("nom\nDurand\n");

        assertThat(table.separator()).isEqualTo(',');
        assertThat(table.columns()).containsExactly("nom");
    }

    @Test
    void readsAFileWhoseLastLineHasNoNewline() {
        CsvParser.Table table = CsvParser.parse("prenom,nom\nAmélie,Durand");

        assertThat(table.rows()).hasSize(1);
    }

    /** Ten thousand rows are read in one pass; nothing here is quadratic. */
    @Test
    void readsALargeFile() {
        StringBuilder builder = new StringBuilder("prenom,nom\n");
        for (int i = 0; i < 10_000; i++) {
            builder.append("Prenom").append(i).append(",Nom").append(i).append('\n');
        }

        CsvParser.Table table = CsvParser.parse(builder.toString());

        assertThat(table.rows()).hasSize(10_000);
        assertThat(table.rows().get(9_999).values()).containsExactly("Prenom9999", "Nom9999");
        assertThat(table.rows().get(9_999).line()).isEqualTo(10_001);
    }

    @Test
    void detectSeparatorIgnoresWhatIsInsideQuotes() {
        assertThat(CsvParser.detectSeparator("a;b\n\"x,y,z,w\";\"p,q\"")).isEqualTo(';');
    }

    @Test
    void valueOutsideTheRowIsEmptyRatherThanAFailure() {
        CsvParser.Table table = CsvParser.parse("nom\nDurand\n");
        List<CsvParser.Row> rows = table.rows();

        assertThat(rows.get(0).value(7)).isEmpty();
        assertThat(rows.get(0).value(-1)).isEmpty();
    }
}
