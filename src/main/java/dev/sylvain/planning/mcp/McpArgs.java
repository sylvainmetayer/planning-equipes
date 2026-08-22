package dev.sylvain.planning.mcp;

import dev.sylvain.planning.service.BusinessError;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import dev.sylvain.planning.domain.FenetreHoraire;

/**
 * Parsing of the scalar tool arguments an MCP client sends as plain strings.
 *
 * <p>Dates, times and enums are taken as strings rather than as their Java
 * types on purpose: an assistant produces text, and a malformed value must
 * come back as a sentence it can act on ("format attendu AAAA-MM-JJ") rather
 * than as a deserialization stack trace from the transport layer.
 */
final class McpArgs {

    private McpArgs() {
    }

    static LocalDate date(String value, String champ) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new BusinessError.Invalid(champ + " : date invalide « " + value + " », format attendu AAAA-MM-JJ");
        }
    }

    static Set<LocalDate> dates(Collection<String> values, String champ) {
        if (values == null) {
            return new HashSet<>();
        }
        return values.stream().map(value -> date(value, champ)).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    static LocalTime heure(String value, String champ) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalTime.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new BusinessError.Invalid(champ + " : heure invalide « " + value + " », format attendu HH:MM");
        }
    }

    static Set<DayOfWeek> joursSemaine(Collection<String> values, String champ) {
        if (values == null) {
            return new TreeSet<>();
        }
        return values.stream()
                .map(value -> enumeration(DayOfWeek.class, value, champ))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /**
     * Reads the compact {@code "10:00-12:00,14:00-"} window syntax shared by
     * the stand-horaire and créneau-recurrence tools. A rule routinely carries
     * two windows (the midday break), which named arguments would force into
     * an arbitrary maximum — hence a string.
     *
     * @param finRequise when {@code true}, the open-ended {@code "14:00-"}
     *                   form is rejected. A stand horaire may run "until
     *                   closing time" because it is evaluated <em>against</em>
     *                   a créneau; a créneau has nothing outer to inherit an
     *                   end from — it <em>is</em> the day's amplitude — so
     *                   leaving its end open would be meaningless rather than
     *                   convenient.
     */
    static List<FenetreHoraire> fenetres(String fenetres, boolean finRequise) {
        if (fenetres == null || fenetres.isBlank()) {
            throw new BusinessError.Invalid("fenetres est requis, ex. « 10:00-12:00,14:00-18:00 »");
        }
        List<FenetreHoraire> result = new ArrayList<>();
        for (String morceau : fenetres.split(",")) {
            String fenetre = morceau.trim();
            if (fenetre.isEmpty()) {
                continue;
            }
            int separateur = fenetre.indexOf('-');
            if (separateur < 0) {
                throw new BusinessError.Invalid("Fenêtre invalide « " + fenetre + " » : attendu "
                        + (finRequise ? "« HH:MM-HH:MM »" : "« HH:MM-HH:MM » ou « HH:MM- »"));
            }
            String debut = fenetre.substring(0, separateur).trim();
            String fin = fenetre.substring(separateur + 1).trim();
            if (fin.isEmpty() && finRequise) {
                throw new BusinessError.Invalid("Fenêtre invalide « " + fenetre + " » : une heure de fin est "
                        + "obligatoire ici. La forme ouverte « HH:MM- » n'existe que pour les horaires de stand, "
                        + "qui se lisent au regard d'un créneau ; un créneau est lui-même l'amplitude du jour.");
            }
            result.add(new FenetreHoraire(heure(debut, "fenetres.heureDebut"),
                    fin.isEmpty() ? null : heure(fin, "fenetres.heureFin")));
        }
        if (result.isEmpty()) {
            throw new BusinessError.Invalid("fenetres ne contient aucune fenêtre exploitable");
        }
        return result;
    }

    static <E extends Enum<E>> E enumeration(Class<E> type, String value, String champ) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Arrays.stream(type.getEnumConstants())
                .filter(constant -> constant.name().equalsIgnoreCase(value.trim()))
                .findFirst()
                .orElseThrow(() -> new BusinessError.Invalid(champ + " : valeur inconnue « " + value
                        + " », valeurs possibles : "
                        + Arrays.stream(type.getEnumConstants()).map(Enum::name).collect(Collectors.joining(", "))));
    }
}
