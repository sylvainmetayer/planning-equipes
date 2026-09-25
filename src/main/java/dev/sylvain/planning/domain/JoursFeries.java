package dev.sylvain.planning.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
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
 * <i>[JUR-7 non vérifié — à faire valider ; à instruire spécifiquement pour
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
 * décret 83-1003 <b>[JUR-8 non vérifié — à faire valider]</b>) is likewise out of
 * scope for the same reason.</li>
 * </ul>
 */
public final class JoursFeries {

    /** One public holiday and what it is called — « Fête nationale », « Lundi de Pentecôte ». */
    public record PublicHoliday(LocalDate date, String label) {}

    private static final Map<Integer, SortedMap<LocalDate, String>> CACHE = new ConcurrentHashMap<>();

    private JoursFeries() {}

    /**
     * True when the date is one of the eleven public holidays of art. L3133-1
     * applicable in metropolitan France outside Alsace-Moselle.
     */
    public static boolean isFerieInFrance(LocalDate date) {
        return date != null && byYear(date.getYear()).containsKey(date);
    }

    /** The public holidays of a given year; computed once per year and cached. */
    public static Set<LocalDate> joursFeries(int annee) {
        return byYear(annee).keySet();
    }

    /**
     * The name of the holiday falling on {@code date}, empty on any other day.
     * The names live next to the computation so that a holiday added later — a
     * regional one — cannot be computed without being named.
     */
    public static Optional<String> label(LocalDate date) {
        return date == null
                ? Optional.empty()
                : Optional.ofNullable(byYear(date.getYear()).get(date));
    }

    /** The holidays between two dates, both included, in calendar order — across years when the range spans them. */
    public static List<PublicHoliday> between(LocalDate debut, LocalDate fin) {
        List<PublicHoliday> feries = new ArrayList<>();
        if (debut == null || fin == null || fin.isBefore(debut)) {
            return feries;
        }
        for (int annee = debut.getYear(); annee <= fin.getYear(); annee++) {
            byYear(annee)
                    .subMap(debut, fin.plusDays(1))
                    .forEach((date, nom) -> feries.add(new PublicHoliday(date, nom)));
        }
        return feries;
    }

    private static SortedMap<LocalDate, String> byYear(int annee) {
        return CACHE.computeIfAbsent(annee, JoursFeries::compute);
    }

    private static SortedMap<LocalDate, String> compute(int annee) {
        LocalDate paques = paques(annee);
        TreeMap<LocalDate, String> feries = new TreeMap<>();
        // Fixed dates (art. L3133-1).
        feries.put(LocalDate.of(annee, 1, 1), "Jour de l'an");
        feries.put(LocalDate.of(annee, 5, 1), "Fête du Travail");
        feries.put(LocalDate.of(annee, 5, 8), "Victoire 1945");
        feries.put(LocalDate.of(annee, 7, 14), "Fête nationale");
        feries.put(LocalDate.of(annee, 8, 15), "Assomption");
        feries.put(LocalDate.of(annee, 11, 1), "Toussaint");
        feries.put(LocalDate.of(annee, 11, 11), "Armistice 1918");
        feries.put(LocalDate.of(annee, 12, 25), "Noël");
        // Movable feasts, derived from Easter (art. L3133-1 too). Ascension can
        // land on 1 or 8 May (2008: 1 May): the two names are then joined
        // rather than one of them lost.
        feries.merge(paques.plusDays(1), "Lundi de Pâques", JoursFeries::join);
        feries.merge(paques.plusDays(39), "Ascension", JoursFeries::join);
        feries.merge(paques.plusDays(50), "Lundi de Pentecôte", JoursFeries::join);
        return Collections.unmodifiableSortedMap(feries);
    }

    private static String join(String premier, String second) {
        return premier + " et " + second;
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
