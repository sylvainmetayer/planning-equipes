package dev.sylvain.planning.scenario;

import dev.sylvain.planning.domain.FenetreHoraire;
import dev.sylvain.planning.domain.HorairesRecurrents;
import dev.sylvain.planning.domain.HorairesRecurrents.Regle;
import dev.sylvain.planning.domain.ModeHoraire;
import dev.sylvain.planning.domain.PlageStand;
import dev.sylvain.planning.domain.TypeJoursHoraire;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Link 4 → 5 of {@link ScenarioMigrations} (ADR 0104): a stand's schedule is a
 * list of dated {@code plages}. The three keys that used to say it —
 * {@code horaires} (recurring rules), {@code ouvertures} and
 * {@code indisponibilites} (dated windows) — become one {@code plages:} list,
 * the rules being expanded over the days of the file's {@code creneaux} exactly
 * as 3.x resolved them at every read ({@link HorairesRecurrents}).
 *
 * <p>The rules stay under {@code horaires}, which is now the stand's model (ADR
 * 0104): a file with <b>no timeslot</b> has no day to expand them on, and the
 * application unfolds them when the edition gains days. Nothing is dropped,
 * nothing is said.</p>
 */
final class ScenarioPlagesLink {

    private ScenarioPlagesLink() {}

    @SuppressWarnings("unchecked")
    static Map<String, Object> convert(Map<String, Object> document, List<String> warnings) {
        Object stands = document.get("stands");
        if (!(stands instanceof List<?> liste) || liste.isEmpty()) {
            return document;
        }
        Set<LocalDate> jours = days(ScenarioYaml.normaliseDates(document.get("creneaux")));
        List<Object> converted = new ArrayList<>();
        for (Object stand : liste) {
            converted.add(
                    stand instanceof Map<?, ?> map
                            ? convertStand((Map<String, Object>) ScenarioYaml.normaliseDates(map), jours)
                            : stand);
        }
        Map<String, Object> out = new LinkedHashMap<>(document);
        out.put("stands", converted);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> convertStand(Map<String, Object> stand, Set<LocalDate> jours) {
        List<Map<String, Object>> horaires = maps(stand.get("horaires"));
        List<Map<String, Object>> ouvertures = maps(stand.get("ouvertures"));
        List<Map<String, Object>> indisponibilites = maps(stand.get("indisponibilites"));
        if (horaires.isEmpty() && ouvertures.isEmpty() && indisponibilites.isEmpty()) {
            return stand;
        }
        // The dated windows travel as the author wrote them, so that a value the reader refuses is refused
        // under its own path; the rules only need to know which days they name.
        List<Map<String, Object>> plages = new ArrayList<>();
        List<PlageStand> joursNommes = new ArrayList<>();
        for (Map<String, Object> indispo : indisponibilites) {
            plages.add(rawPlage(indispo, ModeHoraire.FERMETURE));
            nameDay(joursNommes, indispo);
        }
        for (Map<String, Object> ouverture : ouvertures) {
            plages.add(rawPlage(ouverture, ModeHoraire.OUVERTURE));
            nameDay(joursNommes, ouverture);
        }
        List<Regle> regles = new ArrayList<>();
        for (Map<String, Object> horaire : horaires) {
            regles.add(rule(horaire));
        }
        // The rules stay the stand's model, as written, and are unfolded on the days the file has (none
        // without a timeslot): the file reads as the database the migration converted.
        List<PlageStand> expanded = HorairesRecurrents.expand(regles, joursNommes, jours);
        expanded.subList(joursNommes.size(), expanded.size()).forEach(plage -> plages.add(toMap(plage)));
        Map<String, Object> out = new LinkedHashMap<>(stand);
        out.remove("ouvertures");
        out.remove("indisponibilites");
        if (!plages.isEmpty()) {
            out.put("plages", plages);
        }
        return out;
    }

    /** A dated window of 3.x as a plage: the same keys, the mode added. */
    private static Map<String, Object> rawPlage(Map<String, Object> fenetre, ModeHoraire mode) {
        Map<String, Object> plage = new LinkedHashMap<>();
        plage.put("date", fenetre.get("date"));
        plage.put("mode", mode.name());
        fenetre.forEach((cle, valeur) -> plage.putIfAbsent(cle, valeur));
        return plage;
    }

    /** Records that a dated window names a day, when it names a readable one (the reader reports the others). */
    private static void nameDay(List<PlageStand> joursNommes, Map<String, Object> fenetre) {
        try {
            joursNommes.add(PlageStand.fermeture(null, date(fenetre.get("date")), LocalTime.MIDNIGHT, null, null));
        } catch (ScenarioFormatException e) {
            // Left to the binding, which names the field.
        }
    }

    private static Map<String, Object> toMap(PlageStand plage) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("date", plage.getDate().toString());
        map.put("mode", plage.getMode().name());
        map.put("heureDebut", plage.getHeureDebut().toString());
        if (plage.getHeureFin() != null) {
            map.put("heureFin", plage.getHeureFin().toString());
        }
        if (plage.getEffectif() != null) {
            map.put("effectif", plage.getEffectif());
        }
        if (plage.getMotif() != null) {
            map.put("motif", plage.getMotif());
        }
        if (plage.isDepliee()) {
            map.put("depliee", true);
        }
        return map;
    }

    private static Regle rule(Map<String, Object> horaire) {
        Object mode = horaire.get("mode");
        if (mode == null) {
            throw new ScenarioFormatException("Champ manquant: stands.horaires.mode (OUVERTURE ou FERMETURE)");
        }
        List<FenetreHoraire> fenetres = new ArrayList<>();
        List<Map<String, Object>> liste = maps(horaire.get("fenetres"));
        if (liste.isEmpty()) {
            throw new ScenarioFormatException("Champ manquant: stands.horaires.fenetres (au moins une fenêtre)");
        }
        for (Map<String, Object> fenetre : liste) {
            if (fenetre.get("heureDebut") == null) {
                throw new ScenarioFormatException("Champ manquant: stands.horaires.fenetres.heureDebut");
            }
            fenetres.add(new FenetreHoraire(
                    time(fenetre.get("heureDebut")),
                    optionalTime(fenetre.get("heureFin")),
                    integer(fenetre.get("effectif"))));
        }
        Set<DayOfWeek> joursSemaine = new TreeSet<>();
        for (Object jour : list(horaire.get("joursSemaine"))) {
            joursSemaine.add(parse("stands.horaires.joursSemaine", jour, DayOfWeek::valueOf));
        }
        Set<LocalDate> dates = new TreeSet<>();
        for (Object date : list(horaire.get("dates"))) {
            dates.add(date(date));
        }
        return new Regle(
                parse("stands.horaires.mode", mode, ModeHoraire::valueOf),
                horaire.get("jours") == null
                        ? TypeJoursHoraire.TOUS
                        : parse("stands.horaires.jours", horaire.get("jours"), TypeJoursHoraire::valueOf),
                joursSemaine,
                horaire.get("dateDebut") == null ? null : date(horaire.get("dateDebut")),
                horaire.get("dateFin") == null ? null : date(horaire.get("dateFin")),
                dates,
                fenetres,
                text(horaire.get("motif")));
    }

    /** The days a resolution covers: those of the file's créneaux, and the morning after one that crosses midnight. */
    private static Set<LocalDate> days(Object creneaux) {
        Set<LocalDate> jours = new TreeSet<>();
        for (Map<String, Object> creneau : maps(creneaux)) {
            try {
                if (creneau.get("date") != null) {
                    jours.addAll(HorairesRecurrents.datesConcernees(
                            date(creneau.get("date")),
                            optionalTime(creneau.get("heureDebut")),
                            optionalTime(creneau.get("heureFin"))));
                }
            } catch (ScenarioFormatException e) {
                // An unreadable timeslot is reported by the binding, under its own path.
            }
        }
        return jours;
    }

    /* ------------------------------ raw values ------------------------------ */

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object value) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list(value)) {
            if (item instanceof Map<?, ?> map) {
                out.add((Map<String, Object>) map);
            }
        }
        return out;
    }

    private static List<?> list(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private static Integer integer(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static LocalDate date(Object value) {
        return parse("une date", value, LocalDate::parse);
    }

    /** An hour as the author wrote it: {@code 9:30} reads like {@code 09:30}, as it did before the link. */
    private static LocalTime time(Object value) {
        return parse("une heure", value, texte -> LocalTime.parse(texte.matches("\\d:\\d\\d.*") ? "0" + texte : texte));
    }

    private static LocalTime optionalTime(Object value) {
        return value == null || value.toString().isBlank() ? null : time(value);
    }

    private static <T> T parse(String what, Object value, java.util.function.Function<String, T> parser) {
        try {
            return parser.apply(String.valueOf(value));
        } catch (DateTimeParseException | IllegalArgumentException e) {
            throw new ScenarioFormatException(
                    "Valeur illisible pour " + what + " d'un horaire de stand : « " + value + " ».", e);
        }
    }
}
