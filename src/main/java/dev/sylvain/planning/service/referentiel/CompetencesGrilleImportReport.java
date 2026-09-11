package dev.sylvain.planning.service.referentiel;

import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * What the competences matrix would do, animateur by animateur — and, once
 * applied, what it did. Same shape for the preview and the write,
 * {@link #applied} telling them apart.
 *
 * @param columns  every column past the first, with the typologie it landed on — or why it did not
 * @param rows     one entry per animateur row, in file order
 * @param warnings what concerns the import as a whole rather than one row
 */
@Schema(requiredProperties = {"accepted", "applied", "rejected", "total", "unchanged"})
public record CompetencesGrilleImportReport(
        boolean applied,
        String separator,
        List<ImportedCompetencesColumn> columns,
        int total,
        int accepted,
        int unchanged,
        int rejected,
        List<ImportedCompetencesRow> rows,
        List<String> warnings) {

    /**
     * One column of the file: the header read, and the typologie it names.
     *
     * @param typologieId {@code null} when the header names no typologie of the edition — the column is then ignored
     */
    @Schema(requiredProperties = {"index"})
    public record ImportedCompetencesColumn(int index, String label, String typologieId, String reason) {}

    public enum ImportCompetencesAction {
        /** The row names an animateur of the edition and changes at least one of their appreciations. */
        UPDATED,
        /** The row names an animateur but states nothing they do not already carry; nothing is written. */
        UNCHANGED,
        /** The row is refused; nothing of it is written. */
        REJECTED
    }

    /**
     * @param cellules appreciations the row adds or changes, over the matched columns
     */
    @Schema(requiredProperties = {"action", "cellules", "line"})
    public record ImportedCompetencesRow(
            int line,
            String label,
            String animateurId,
            ImportCompetencesAction action,
            List<String> reasons,
            int cellules) {}
}
