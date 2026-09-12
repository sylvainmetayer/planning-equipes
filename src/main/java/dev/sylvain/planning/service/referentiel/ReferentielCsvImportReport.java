package dev.sylvain.planning.service.referentiel;

import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * What a referential CSV file does, or would do: the same record answers the
 * preview and the write, {@link #applied} telling the two apart.
 *
 * <p>Same shape as {@link AnimateurCsvImportReport} on purpose — the three
 * referentials it serves (typologies, emplacements, stands) are small enough
 * that a report per entity would be three near-copies, and the screen renders
 * one table whatever the tab.</p>
 *
 * @param applied      false for the preview, true once the transaction committed
 * @param cible        which referential the file was read against
 * @param columns      the header as it was read, so the screen can name columns
 * @param separator    the separator recognised, for a file a spreadsheet wrote
 * @param total        data rows read
 * @param accepted     rows the import writes
 * @param rejected     rows it refuses, each with its reason
 * @param created      among the accepted, the ones that did not exist
 * @param updated      among the accepted, the ones written over an existing row
 * @param typologiesCreees typologie ids a stand referenced without them existing:
 *                         created with their id as label, and named here so the
 *                         creation is never silent
 */
@Schema(requiredProperties = {"accepted", "applied", "cible", "created", "rejected", "total", "updated"})
public record ReferentielCsvImportReport(
        boolean applied,
        ImportTarget cible,
        List<String> columns,
        String separator,
        int total,
        int accepted,
        int rejected,
        int created,
        int updated,
        List<String> typologiesCreees,
        List<LigneImportee> rows) {

    /** Which referential a file is read against. */
    public enum ImportTarget {
        TYPOLOGIES,
        EMPLACEMENTS,
        STANDS
    }

    /** What one row of the file does. */
    public enum ActionImport {
        /** Nothing of this id exists yet: the row is created. */
        CREE,
        /** The id already exists: the row is written over it. */
        MIS_A_JOUR,
        /** The row is refused; nothing of it is written. */
        REFUSE
    }

    /**
     * One data row, as the report shows it.
     *
     * @param line    the physical line of the file, counted from 1
     * @param id      the row's identifier, resolved or read; null when unreadable
     * @param libelle how the row names itself, for the operator to find it back
     * @param action  what happens to it
     * @param raisons why it is refused, empty otherwise
     * @param details what is accepted but worth saying — a headcount defaulted,
     *                a typologie about to be created
     */
    @Schema(requiredProperties = {"action", "line"})
    public record LigneImportee(
            int line, String id, String libelle, ActionImport action, List<String> raisons, List<String> details) {}
}
