package dev.sylvain.planning.service;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;

/**
 * What the file would do, row by row — and, once applied, what it did.
 *
 * <p>The same record answers the preview and the write, with {@link #applied}
 * telling the two apart. That is on purpose: a preview whose shape differs
 * from the outcome invites the screen to render one and the operator to read
 * the other. Here the numbers of the preview are exactly the numbers of the
 * write, because the write recomputes this report from the file rather than
 * trusting the one the browser was shown.</p>
 *
 * @param applied    false for the preview (nothing was written), true once the
 *                   transaction committed
 * @param columns    the header as it was read, so the screen can name columns
 * @param mapping    the mapping actually used — proposed or corrected
 * @param separator  the separator recognised, shown so a mis-detection is visible
 * @param total      data rows read, blank lines excluded
 * @param accepted   rows that would be (or were) written
 * @param rejected   rows refused, each with its reasons
 * @param created    accepted rows naming nobody known — new fiches
 * @param updated    accepted rows naming an existing animateur
 * @param deleted    animateurs the edition holds and the file does not, in
 *                   replacement mode only
 * @param rows       one entry per data row, in file order
 * @param warnings   what concerns the import as a whole rather than one row
 */
@Schema(requiredProperties = {"accepted", "applied", "created", "deleted", "rejected", "total", "updated"})
public record AnimateurCsvImportReport(
        boolean applied,
        List<String> columns,
        AnimateurCsvMapping mapping,
        String separator,
        int total,
        int accepted,
        int rejected,
        int created,
        int updated,
        int deleted,
        List<ImportedRow> rows,
        List<String> warnings) {

    /** What one row of the file does. */
    public enum ImportAction {
        /** No animateur of this edition matches: a fiche is created. */
        CREATED,
        /** The row matches an existing animateur, whose fiche is updated in place. */
        UPDATED,
        /** The row is refused; nothing of it is written. */
        REJECTED
    }

    /**
     * One data row of the file, as the report shows it.
     *
     * @param line               the physical line of the file, counted from 1
     * @param label              how the row names its person, for the operator to find it
     * @param animateurId        the fiche it lands on — resolved or generated; null when rejected
     * @param action             what happens to it
     * @param reasons            why it was refused, empty otherwise
     * @param warnings           what is accepted but worth saying
     * @param joursIndisponibles the off days the fiche would carry after the
     *                           import — merged or replaced, so the operator
     *                           reads the outcome and not the input
     */
    public record ImportedRow(
            int line,
            String label,
            String animateurId,
            ImportAction action,
            List<String> reasons,
            List<String> warnings,
            List<LocalDate> joursIndisponibles) {
    }
}
