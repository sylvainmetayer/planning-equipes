package dev.sylvain.planning.service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the organiser's stand matrix as a spreadsheet exports it: one row per
 * stand, one column per (date, band), an integer per cell. Pure parsing — no
 * créneau, no stand is looked up here; {@link StandGrilleImportService} does
 * the matching.
 *
 * <p>Two header shapes are read. The one a spreadsheet copy produces: a date
 * line where a merged cell leaves the following cells empty, then a band
 * line ({@code 10:00-12:00}). And the flat one, {@code 2026-07-08 10:00-12:00}
 * in a single header cell. Dates read as ISO or as {@code 08/07/2026}; hours
 * as {@code 10:00}, {@code 10h} or {@code 10h30}; {@code 24:00} and
 * {@code 00:00} both mean midnight as an end.</p>
 */
public final class GrilleCsv {

    private GrilleCsv() {
    }

    /** One column of the matrix: its position, what the header said, and the (date, band) it names — or not. */
    public record Colonne(int index, String libelle, LocalDate date, LocalTime heureDebut, LocalTime heureFin) {

        public boolean namesCreneau() {
            return date != null && heureDebut != null && heureFin != null;
        }
    }

    /** One data row: the physical line, the stand as written, and the raw cell under each column. */
    public record Ligne(int line, String stand, List<String> cellules) {
    }

    public record Matrice(String separator, List<Colonne> colonnes, List<Ligne> lignes) {
    }

    private static final Pattern BANDE = Pattern.compile(
            "^\\s*(\\d{1,2}(?:[:h.]\\d{0,2})?)\\s*[-–→]\\s*(\\d{1,2}(?:[:h.]\\d{0,2})?)\\s*$");
    private static final Pattern DATE_ET_BANDE = Pattern.compile("^\\s*(\\S+)\\s+(.+)$");
    private static final List<DateTimeFormatter> DATES = List.of(
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("d/M/uuuu").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("d-M-uuuu").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("d.M.uuuu").withResolverStyle(ResolverStyle.STRICT));

    public static Matrice parse(String content) {
        CsvParser.Table table = CsvParser.parse(content);
        List<String> premiere = table.columns();
        List<CsvParser.Row> rows = new ArrayList<>(table.rows());
        List<Colonne> colonnes = new ArrayList<>();
        if (!rows.isEmpty() && looksLikeBands(rows.get(0).values())) {
            // Two header lines: dates (merged cells filled forward), then bands.
            List<String> bandes = rows.remove(0).values();
            LocalDate courante = null;
            for (int index = 1; index < Math.max(premiere.size(), bandes.size()); index++) {
                String dateTexte = index < premiere.size() ? premiere.get(index).trim() : "";
                if (!dateTexte.isEmpty()) {
                    courante = date(dateTexte);
                }
                String bande = index < bandes.size() ? bandes.get(index).trim() : "";
                LocalTime[] heures = bande(bande);
                String libelle = (dateTexte.isEmpty() && courante != null ? courante.toString() : dateTexte) + " " + bande;
                colonnes.add(new Colonne(index, libelle.trim(), courante,
                        heures == null ? null : heures[0], heures == null ? null : heures[1]));
            }
        } else {
            for (int index = 1; index < premiere.size(); index++) {
                String libelle = premiere.get(index).trim();
                Matcher m = DATE_ET_BANDE.matcher(libelle);
                LocalDate date = null;
                LocalTime[] heures = null;
                if (m.matches()) {
                    date = date(m.group(1));
                    heures = bande(m.group(2));
                }
                colonnes.add(new Colonne(index, libelle, date, heures == null ? null : heures[0],
                        heures == null ? null : heures[1]));
            }
        }
        List<Ligne> lignes = new ArrayList<>();
        for (CsvParser.Row row : rows) {
            if (row.blank()) {
                continue;
            }
            List<String> cellules = new ArrayList<>();
            for (Colonne colonne : colonnes) {
                cellules.add(row.value(colonne.index()));
            }
            lignes.add(new Ligne(row.line(), row.value(0).trim(), cellules));
        }
        return new Matrice(String.valueOf(table.separator()), colonnes, lignes);
    }

    /** A line whose cells past the first are bands (or blank) is the second header, not a stand. */
    private static boolean looksLikeBands(List<String> values) {
        boolean uneBande = false;
        for (int index = 1; index < values.size(); index++) {
            String cellule = values.get(index).trim();
            if (cellule.isEmpty()) {
                continue;
            }
            if (bande(cellule) == null) {
                return false;
            }
            uneBande = true;
        }
        return uneBande;
    }

    static LocalDate date(String texte) {
        for (DateTimeFormatter format : DATES) {
            try {
                return LocalDate.parse(texte.trim(), format);
            } catch (DateTimeParseException ignored) {
                // next format
            }
        }
        return null;
    }

    /** {@code 10:00-12:00} → the two times; {@code null} when the text is not a band. */
    static LocalTime[] bande(String texte) {
        Matcher m = BANDE.matcher(texte == null ? "" : texte);
        if (!m.matches()) {
            return null;
        }
        LocalTime debut = heure(m.group(1));
        LocalTime fin = heure(m.group(2));
        return debut == null || fin == null ? null : new LocalTime[] {debut, fin};
    }

    /** {@code 10}, {@code 10h}, {@code 10h30}, {@code 10:30}, {@code 24:00} (as 00:00). */
    static LocalTime heure(String texte) {
        Matcher m = Pattern.compile("^(\\d{1,2})(?:[:h.](\\d{0,2}))?$").matcher(texte.trim().toLowerCase(Locale.ROOT));
        if (!m.matches()) {
            return null;
        }
        int heures = Integer.parseInt(m.group(1));
        String minutesTexte = m.group(2) == null || m.group(2).isEmpty() ? "0" : m.group(2);
        int minutes = Integer.parseInt(minutesTexte.length() == 1 ? minutesTexte + "0" : minutesTexte);
        if (heures == 24 && minutes == 0) {
            return LocalTime.MIDNIGHT;
        }
        if (heures > 23 || minutes > 59) {
            return null;
        }
        return LocalTime.of(heures, minutes);
    }

    /**
     * What a cell means: blank, a dash or a zero close the stand; digits are a
     * headcount; anything else is not a value, and {@code null} is returned
     * for the caller to refuse the row.
     */
    public static CelluleLue cellule(String texte) {
        String propre = texte == null ? "" : texte.trim();
        if (propre.isEmpty() || propre.equals("-") || propre.equals("—") || propre.equals("0")) {
            return new CelluleLue(null, true);
        }
        if (propre.matches("\\d+")) {
            return new CelluleLue(Integer.parseInt(propre), true);
        }
        return new CelluleLue(null, false);
    }

    /** {@code lisible} false: the text is not a value at all. */
    public record CelluleLue(Integer effectif, boolean lisible) {
    }
}
