package dev.sylvain.planning.service.referentiel;

import java.time.LocalDate;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

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
 * @param doublonsProbables accepted rows flagged as a probable duplicate or
 *                   namesake, a row whose name alone replaces a fiche's
 *                   address included — a warning, never a refusal: see
 *                   {@link ImportedRow#doublonDe}
 * @param rows       one entry per data row, in file order
 * @param warnings   what concerns the import as a whole rather than one row
 */
@Schema(
        requiredProperties = {
            "accepted",
            "applied",
            "created",
            "deleted",
            "doublonsProbables",
            "rejected",
            "total",
            "updated"
        })
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
        int doublonsProbables,
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
     * @param animateurId        the fiche it lands on — null when rejected, and on a preview
     *                           for a fiche the write will create, whose id is drawn then
     * @param action             what happens to it
     * @param reasons            why it was refused, empty otherwise
     * @param warnings           what is accepted but worth saying
     * @param joursIndisponibles the off days the fiche would carry after the
     *                           import — merged or replaced, so the operator
     *                           reads the outcome and not the input
     * @param doublonDe          who this row probably duplicates, structured so the
     *                           screen links to the other row or to the fiche rather
     *                           than parsing the sentence of {@code warnings}; empty
     *                           when nothing is flagged, always empty on a rejected row
     */
    public record ImportedRow(
            int line,
            String label,
            String animateurId,
            ImportAction action,
            List<String> reasons,
            List<String> warnings,
            List<LocalDate> joursIndisponibles,
            List<ProbableDuplicate> doublonDe) {}

    /** Why a row was flagged against somebody else — each case has its own sentence in the row's warnings. */
    public enum DuplicateKind {
        /** Another accepted row of the file carries the same first name, last name and birth date. */
        ROW,
        /** A fiche the import keeps carries the same three, and this row would make a second one of them. */
        FICHE,
        /**
         * Same as {@link #FICHE}, but the fiche is one the full replacement deletes: not a duplicate
         * after the write, the announcement of a person deleted and then described again.
         */
        REPLACED,
        /** The row lands on a fiche by its name alone, and that fiche was born on another day. */
        NAMESAKE,
        /**
         * The row lands on a fiche by its name alone — its address names nobody — and replaces that
         * fiche's address: the same person moved, or a namesake about to receive somebody else's
         * access codes and mails. Only when nothing else points to that fiche already.
         */
        NEW_ADDRESS
    }

    /**
     * One probable duplicate of a row: exactly one of {@code line} and
     * {@code animateurId} is set — the other row of the file, or the fiche.
     * Never a name nor a birth date: the reference is what the screen needs to
     * draw a link, and the sentence beside it says the rest.
     */
    @Schema(requiredProperties = {"kind"})
    public record ProbableDuplicate(DuplicateKind kind, Integer line, String animateurId) {

        static ProbableDuplicate row(int line) {
            return new ProbableDuplicate(DuplicateKind.ROW, line, null);
        }

        static ProbableDuplicate fiche(DuplicateKind kind, String animateurId) {
            return new ProbableDuplicate(kind, null, animateurId);
        }
    }
}
