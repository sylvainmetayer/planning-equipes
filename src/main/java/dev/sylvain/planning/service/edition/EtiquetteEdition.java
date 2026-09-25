package dev.sylvain.planning.service.edition;

import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.Locale;

/**
 * Which edition a planning is the planning <b>of</b>: its name, and the days
 * its event spans (issue #608).
 *
 * <p>An animateur who came back from one year to the next holds two espace
 * links in their mailbox — each edition mails its own, and none of them ever
 * expires. Until this record existed the two pages were rigorously identical,
 * « votre planning » and nothing else, so the only way to tell which was this
 * year's was to read the schedule and recognise it. Somebody checking their
 * hours the evening before got a perfectly coherent planning, simply not the
 * right one.</p>
 *
 * <p>Nothing is stored for it: an {@code Edition} carries neither dates nor an
 * « ongoing » flag, so the span is derived from the créneaux, exactly as
 * {@link dev.sylvain.planning.service.referentiel.JoursEvenement} defines it
 * (see {@code docs/domaine.md}, « Les bornes de l'édition se dérivent »). An
 * edition with no créneau therefore has a name and no dates, which is said
 * rather than invented.</p>
 *
 * @param nom   the edition's display name, {@code null} when it cannot be
 *              resolved — a page and a document are still worth serving
 * @param debut first day of the event, {@code null} when the edition holds no
 *              créneau
 * @param fin   last day, {@code null} under the same condition
 */
public record EtiquetteEdition(String nom, LocalDate debut, LocalDate fin) {

    /** Neither name nor dates: what a caller shows when the edition cannot be read at all. */
    public static final EtiquetteEdition INCONNUE = new EtiquetteEdition(null, null, null);

    /** True when there is nothing to show — no name and no span. */
    public boolean isEmpty() {
        return (nom == null || nom.isBlank()) && debut == null;
    }

    /**
     * The whole label, as the French documents print it: « Festival 26 — du 1er au
     * 16 février 2026 ». {@code null} when there is nothing to say.
     *
     * <p>The interface does not use this: the espace is bilingual and formats
     * the three fields itself. The PDF and the ICS are French documents
     * produced server-side, and this is their one wording.</p>
     */
    public String libelle() {
        String periode = periode();
        if (nom == null || nom.isBlank()) {
            return periode;
        }
        return periode == null ? nom : nom + " — " + periode;
    }

    /**
     * The span alone, spelled out: « du 1er au 16 février 2026 », « le 14
     * février 2026 » for a one-day event, and the year repeated on both sides
     * when the event straddles a new year.
     *
     * <p>The year is always written, even though an edition usually names it
     * too. It is the one thing that tells two editions apart, and it is
     * precisely a reader unsure of which year they are looking at that this
     * label is for.</p>
     */
    public String periode() {
        if (debut == null || fin == null) {
            return null;
        }
        if (debut.equals(fin)) {
            return "le " + jourMoisAnnee(debut);
        }
        if (debut.getYear() != fin.getYear()) {
            return "du " + jourMoisAnnee(debut) + " au " + jourMoisAnnee(fin);
        }
        if (debut.getMonthValue() != fin.getMonthValue()) {
            return "du " + jourMois(debut) + " au " + jourMoisAnnee(fin);
        }
        return "du " + jour(debut) + " au " + jourMoisAnnee(fin);
    }

    private static String jourMoisAnnee(LocalDate date) {
        return jourMois(date) + " " + date.getYear();
    }

    private static String jourMois(LocalDate date) {
        return jour(date) + " " + date.getMonth().getDisplayName(TextStyle.FULL, Locale.FRENCH);
    }

    /** « 1er » on the first of the month, the bare number on every other day — French ordinals. */
    private static String jour(LocalDate date) {
        return date.getDayOfMonth() == 1 ? "1er" : String.valueOf(date.getDayOfMonth());
    }
}
