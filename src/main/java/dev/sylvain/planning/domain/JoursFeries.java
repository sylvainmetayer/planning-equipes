package dev.sylvain.planning.domain;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * French public holidays ("jours de fête reconnus par la loi"), listed by the
 * Code du travail art. <b>L3133-1</b>.
 *
 * <p>Used by {@code LegalConstraints.travailInterditJourFerieMineur} to enforce
 * art. <b>L3164-6</b>: <i>« Les jeunes travailleurs ne peuvent travailler les
 * jours de fête reconnus par la loi. »</i></p>
 *
 * <h2>Scope decisions</h2>
 * <ul>
 * <li><b>No sectoral derogation.</b> Art. R3164-2 allows derogations in
 * sectors set by decree. Whether event management / animation is among them
 * <b>has not been established</b> — the audit flags it as
 * <i>[non vérifié — à faire validate ; à instruire spécifiquement pour
 * l'événementiel et l'animation]</i>. Until a lawyer settles it, the most
 * protective default applies: a plain ban, with no derogation, and no way to
 * configure one. Coding a derogation on an unverified basis would be worse
 * than not coding it.</li>
 * <li><b>Métropole only.</b> Good Friday and 26 December are public holidays
 * only in Alsace-Moselle (Haut-Rhin, Bas-Rhin, Moselle). The model carries no
 * notion of region, so they are deliberately left out rather than applied
 * nationwide. Adding them requires a region on the event or the stand
 * first.</li>
 * <li><b>Abolition de l'esclavage</b> (overseas départements, art. L3422-2 and
 * décret 83-1003 <b>[non vérifié — à faire validate]</b>) is likewise out of
 * scope for the same reason.</li>
 * </ul>
 */
public final class JoursFeries {

    private static final Map<Integer, Set<LocalDate>> CACHE = new ConcurrentHashMap<>();

    private JoursFeries() {
    }

    /**
     * True when the date is one of the eleven public holidays of art. L3133-1
     * applicable in metropolitan France outside Alsace-Moselle.
     */
    public static boolean isFerieInFrance(LocalDate date) {
        return date != null && joursFeries(date.getYear()).contains(date);
    }

    /** The public holidays of a given year; computed once per year and cached. */
    public static Set<LocalDate> joursFeries(int annee) {
        return CACHE.computeIfAbsent(annee, JoursFeries::compute);
    }

    private static Set<LocalDate> compute(int annee) {
        LocalDate paques = paques(annee);
        Set<LocalDate> feries = new HashSet<>();
        // Fixed dates (art. L3133-1).
        feries.add(LocalDate.of(annee, 1, 1));    // Jour de l'an
        feries.add(LocalDate.of(annee, 5, 1));    // Fête du Travail
        feries.add(LocalDate.of(annee, 5, 8));    // Victoire 1945
        feries.add(LocalDate.of(annee, 7, 14));   // Fête nationale
        feries.add(LocalDate.of(annee, 8, 15));   // Assomption
        feries.add(LocalDate.of(annee, 11, 1));   // Toussaint
        feries.add(LocalDate.of(annee, 11, 11));  // Armistice 1918
        feries.add(LocalDate.of(annee, 12, 25));  // Noël
        // Movable feasts, derived from Easter (art. L3133-1 too).
        feries.add(paques.plusDays(1));           // Lundi de Pâques
        feries.add(paques.plusDays(39));          // Jeudi de l'Ascension
        feries.add(paques.plusDays(50));          // Lundi de Pentecôte
        return Set.copyOf(feries);
    }

    /**
     * Easter Sunday, by the anonymous Gregorian computus (Meeus/Jones/Butcher).
     * Valid for every year of the Gregorian calendar.
     */
    static LocalDate paques(int annee) {
        int a = annee % 19;
        int b = annee / 100;
        int c = annee % 100;
        int d = b / 4;
        int e = b % 4;
        int f = (b + 8) / 25;
        int g = (b - f + 1) / 3;
        int h = (19 * a + b - d - g + 15) % 30;
        int i = c / 4;
        int k = c % 4;
        int l = (32 + 2 * e + 2 * i - h - k) % 7;
        int m = (a + 11 * h + 22 * l) / 451;
        int mois = (h + l - 7 * m + 114) / 31;
        int jour = ((h + l - 7 * m + 114) % 31) + 1;
        return LocalDate.of(annee, mois, jour);
    }
}
