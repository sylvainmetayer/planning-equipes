package dev.sylvain.planning.service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * What the matrix would do, stand by stand — and, once applied, what it did.
 * Same shape for the preview and the write, {@link #applied} telling them apart.
 *
 * @param columns          every column past the first, with the créneau it landed on — or why it did not
 * @param creneauxAbsents  créneaux of the edition the file has no column for: their cells are kept as they are
 * @param rows             one entry per stand row, in file order
 * @param warnings         what concerns the import as a whole rather than one row
 */
public record StandGrilleImportReport(
        boolean applied,
        String separator,
        List<ImportedColumn> columns,
        List<String> creneauxAbsents,
        int total,
        int accepted,
        int rejected,
        List<ImportedRow> rows,
        List<String> warnings) {

    /**
     * One column of the file: the (date, band) read, and the créneau it matches.
     *
     * @param creneauId the first créneau the column lands on, {@code null} when it lands on none
     * @param creneaux  how many créneaux it lands on — more than one when the grid is staggered
     *                  into families, the column then carrying the same cell to each of them
     */
    public record ImportedColumn(int index, String label, LocalDate date, LocalTime heureDebut, LocalTime heureFin,
            Long creneauId, int creneaux, String reason) {
    }

    public enum ImportAction {
        /** The row names a stand of the edition, whose whole schedule is rewritten from its cells. */
        UPDATED,
        /** The row is refused; nothing of it is written. */
        REJECTED
    }

    /**
     * @param cellulesOuvertes cells carrying a headcount, over the matched columns
     * @param regles           rules the stand's schedule folds into (0 until the conversion ran)
     * @param exceptions       dated windows left over
     */
    public record ImportedRow(int line, String label, String standId, ImportAction action, List<String> reasons,
            int cellulesOuvertes, int regles, int exceptions, Integer effectifMin, Integer effectifMax) {
    }
}
