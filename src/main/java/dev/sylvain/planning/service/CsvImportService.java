package dev.sylvain.planning.service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.NiveauCompetence;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypologieJeu;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Imports the reference data from CSV files. Each import fully replaces the
 * matching table (and drops the existing assignments, which would otherwise
 * point at rows that no longer exist).
 *
 * <p>
 * Files use a header line, {@code ;} or {@code ,} as separator (auto-detected
 * from the header) and the following columns:
 * <ul>
 * <li>animateurs: {@code id;prenom;nom;dateNaissance;manager;competences;joursIndisponibles}
 * where {@code manager} is {@code true}/{@code false} (optional, defaults to {@code false}),
 * {@code competences} is {@code STRATEGIE:REFERENT|AMBIANCE:AUTONOME} and
 * {@code joursIndisponibles} is {@code 2026-07-02|2026-07-03}</li>
 * <li>stands: {@code id;nom;typologies;effectifMin;effectifMax;reserveMajeurs;premium}
 * where {@code typologies} is {@code STRATEGIE|ENFANT}</li>
 * <li>creneaux: {@code id;jour;date;heureDebut;heureFin}</li>
 * </ul>
 */
@ApplicationScoped
public class CsvImportService {

    private static final String MULTI_VALUE_SEPARATOR = "\\|";

    @Inject
    ReferenceDataRepository repository;

    public int importAnimateurs(String csv) {
        CsvTable table = CsvTable.parse(csv,
                List.of("id", "prenom", "nom", "dateNaissance"),
                List.of("manager", "competences", "joursIndisponibles"));
        List<Animateur> animateurs = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (CsvRow row : table.rows()) {
            Animateur animateur = new Animateur();
            animateur.setId(uniqueId(row, ids));
            animateur.setPrenom(row.required("prenom"));
            animateur.setNom(row.required("nom"));
            animateur.setDateNaissance(row.date("dateNaissance"));
            animateur.setManager(row.bool("manager"));
            animateur.setCompetences(parseCompetences(row));
            animateur.setJoursIndisponibles(parseJoursIndisponibles(row));
            animateurs.add(animateur);
        }
        repository.replaceAnimateurs(animateurs);
        return animateurs.size();
    }

    public int importStands(String csv) {
        CsvTable table = CsvTable.parse(csv,
                List.of("id", "nom", "effectifMin", "effectifMax"),
                List.of("typologies", "reserveMajeurs", "premium"));
        List<Stand> stands = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (CsvRow row : table.rows()) {
            Stand stand = new Stand();
            stand.setId(uniqueId(row, ids));
            stand.setNom(row.required("nom"));
            stand.setEffectifMin(row.integer("effectifMin"));
            stand.setEffectifMax(row.integer("effectifMax"));
            if (stand.getEffectifMin() > stand.getEffectifMax()) {
                throw new IllegalArgumentException(
                        row.prefix() + "effectifMin cannot be greater than effectifMax");
            }
            stand.setReserveMajeurs(row.bool("reserveMajeurs"));
            stand.setPremium(row.bool("premium"));
            Set<TypologieJeu> typologies = new LinkedHashSet<>();
            for (String value : row.multi("typologies")) {
                typologies.add(row.parseEnum("typologies", value, TypologieJeu.class));
            }
            stand.setTypologiesProposees(typologies);
            stands.add(stand);
        }
        repository.replaceStands(stands);
        return stands.size();
    }

    public int importCreneaux(String csv) {
        CsvTable table = CsvTable.parse(csv,
                List.of("id", "jour", "date", "heureDebut", "heureFin"),
                List.of());
        List<Creneau> creneaux = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (CsvRow row : table.rows()) {
            Creneau creneau = new Creneau();
            creneau.setId(uniqueId(row, ids));
            creneau.setJour(row.integer("jour"));
            creneau.setDate(row.date("date"));
            creneau.setHeureDebut(row.time("heureDebut"));
            creneau.setHeureFin(row.time("heureFin"));
            if (!creneau.getHeureFin().isAfter(creneau.getHeureDebut())) {
                throw new IllegalArgumentException(row.prefix() + "heureFin must be after heureDebut");
            }
            creneaux.add(creneau);
        }
        repository.replaceCreneaux(creneaux);
        return creneaux.size();
    }

    private String uniqueId(CsvRow row, Set<String> seen) {
        String id = row.required("id");
        if (!seen.add(id)) {
            throw new IllegalArgumentException(row.prefix() + "duplicated id: " + id);
        }
        return id;
    }

    private Map<TypologieJeu, NiveauCompetence> parseCompetences(CsvRow row) {
        Map<TypologieJeu, NiveauCompetence> competences = new LinkedHashMap<>();
        for (String entry : row.multi("competences")) {
            String[] parts = entry.split(":");
            if (parts.length != 2) {
                throw new IllegalArgumentException(row.prefix()
                        + "competences must use the TYPOLOGIE:NIVEAU format, found: " + entry);
            }
            competences.put(row.parseEnum("competences", parts[0], TypologieJeu.class),
                    row.parseEnum("competences", parts[1], NiveauCompetence.class));
        }
        return competences;
    }

    private Set<LocalDate> parseJoursIndisponibles(CsvRow row) {
        Set<LocalDate> jours = new LinkedHashSet<>();
        for (String entry : row.multi("joursIndisponibles")) {
            try {
                jours.add(LocalDate.parse(entry));
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException(row.prefix()
                        + "joursIndisponibles must contain ISO dates (yyyy-MM-dd), found: " + entry);
            }
        }
        return jours;
    }

    /* ------------------------------ CSV parsing ----------------------------- */

    record CsvTable(List<CsvRow> rows) {

        static CsvTable parse(String csv, List<String> requiredColumns, List<String> optionalColumns) {
            if (csv == null || csv.isBlank()) {
                throw new IllegalArgumentException("The CSV file is empty");
            }
            List<String> lines = csv.lines()
                    .map(line -> line.replace("\uFEFF", ""))
                    .filter(line -> !line.isBlank())
                    .toList();
            if (lines.isEmpty()) {
                throw new IllegalArgumentException("The CSV file is empty");
            }
            String header = lines.get(0);
            char separator = header.indexOf(';') >= 0 ? ';' : ',';
            Map<String, Integer> columns = new LinkedHashMap<>();
            List<String> headerCells = splitLine(header, separator);
            for (int i = 0; i < headerCells.size(); i++) {
                columns.put(normalize(headerCells.get(i)), i);
            }
            List<String> missing = requiredColumns.stream()
                    .filter(column -> !columns.containsKey(normalize(column)))
                    .toList();
            if (!missing.isEmpty()) {
                throw new IllegalArgumentException("Missing CSV column(s): " + String.join(", ", missing));
            }
            List<String> known = new ArrayList<>(requiredColumns);
            known.addAll(optionalColumns);
            List<CsvRow> rows = new ArrayList<>();
            for (int i = 1; i < lines.size(); i++) {
                List<String> cells = splitLine(lines.get(i), separator);
                rows.add(new CsvRow(i + 1, columns, cells, known));
            }
            if (rows.isEmpty()) {
                throw new IllegalArgumentException("The CSV file does not contain any data row");
            }
            return new CsvTable(rows);
        }

        /** Splits a line, honouring RFC 4180 double-quoted cells. */
        static List<String> splitLine(String line, char separator) {
            List<String> cells = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            boolean quoted = false;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == '"') {
                    if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        quoted = !quoted;
                    }
                    continue;
                }
                if (c == separator && !quoted) {
                    cells.add(current.toString().trim());
                    current.setLength(0);
                    continue;
                }
                current.append(c);
            }
            cells.add(current.toString().trim());
            return cells;
        }

        static String normalize(String column) {
            return column == null ? "" : column.trim().toLowerCase(Locale.ROOT);
        }
    }

    record CsvRow(int lineNumber, Map<String, Integer> columns, List<String> cells, List<String> knownColumns) {

        String prefix() {
            return "Line " + lineNumber + ": ";
        }

        String raw(String column) {
            Integer index = columns.get(CsvTable.normalize(column));
            if (index == null || index >= cells.size()) {
                return "";
            }
            return cells.get(index);
        }

        String required(String column) {
            String value = raw(column);
            if (value.isBlank()) {
                throw new IllegalArgumentException(prefix() + "column '" + column + "' is required");
            }
            return value;
        }

        int integer(String column) {
            String value = required(column);
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(prefix() + "column '" + column + "' must be a number, found: "
                        + value);
            }
        }

        boolean bool(String column) {
            String value = raw(column).toLowerCase(Locale.ROOT);
            return value.equals("true") || value.equals("1") || value.equals("oui") || value.equals("yes");
        }

        LocalDate date(String column) {
            String value = required(column);
            try {
                return LocalDate.parse(value);
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException(prefix() + "column '" + column
                        + "' must be an ISO date (yyyy-MM-dd), found: " + value);
            }
        }

        LocalTime time(String column) {
            String value = required(column);
            try {
                return LocalTime.parse(value);
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException(prefix() + "column '" + column
                        + "' must be an ISO time (HH:mm), found: " + value);
            }
        }

        List<String> multi(String column) {
            String value = raw(column);
            if (value.isBlank()) {
                return List.of();
            }
            return java.util.Arrays.stream(value.split(MULTI_VALUE_SEPARATOR))
                    .map(String::trim)
                    .filter(part -> !part.isEmpty())
                    .toList();
        }

        <E extends Enum<E>> E enumValue(String column, Class<E> type) {
            return parseEnum(column, required(column), type);
        }

        <E extends Enum<E>> E parseEnum(String column, String value, Class<E> type) {
            try {
                return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(prefix() + "column '" + column + "' must be one of "
                        + java.util.Arrays.toString(type.getEnumConstants()) + ", found: " + value);
            }
        }
    }
}
