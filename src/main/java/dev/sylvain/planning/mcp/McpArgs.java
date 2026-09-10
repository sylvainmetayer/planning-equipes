package dev.sylvain.planning.mcp;

import dev.sylvain.planning.domain.FenetreHoraire;
import dev.sylvain.planning.service.BusinessError;
import java.time.DayOfWeek;
import java.time.Instant;
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

/**
 * Parsing of the scalar tool arguments an MCP client sends as plain strings.
 *
 * <p>Dates, times and enums are taken as strings rather than as their Java
 * types on purpose: an assistant produces text, and a malformed value must
 * come back as a sentence it can act on ("format attendu AAAA-MM-JJ") rather
 * than as a deserialization stack trace from the transport layer.
 */
final class McpArgs {

    private McpArgs() {}

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

    /** An ISO-8601 instant, as {@code modifieLe} is printed by every view — the precondition of issue #362. */
    static Instant instant(String value, String champ) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new BusinessError.Invalid(champ + " : horodatage invalide « " + value
                    + " », format attendu ISO-8601 (ex. 2026-09-06T10:15:30.123456Z)");
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
        return fenetres(fenetres, finRequise, false);
    }

    /**
     * Same syntax, plus — when {@code effectifAutorise} — an optional
     * {@code @N} suffix naming the seats to staff on that window
     * ({@code « 10:00-12:00@2,14:00-@4 »}): the stand's own windows carry an
     * effectif, a créneau's recurrence does not, and the suffix is refused
     * where it would mean nothing.
     */
    static List<FenetreHoraire> fenetres(String fenetres, boolean finRequise, boolean effectifAutorise) {
        if (fenetres == null || fenetres.isBlank()) {
            throw new BusinessError.Invalid("fenetres est requis, ex. « 10:00-12:00,14:00-18:00 »");
        }
        List<FenetreHoraire> result = new ArrayList<>();
        for (String morceau : fenetres.split(",")) {
            String fenetre = morceau.trim();
            if (fenetre.isEmpty()) {
                continue;
            }
            Integer effectif = null;
            int arobase = fenetre.indexOf('@');
            if (arobase >= 0) {
                if (!effectifAutorise) {
                    throw new BusinessError.Invalid("Fenêtre invalide « " + fenetre + " » : l'effectif « @N » ne "
                            + "se déclare que sur les fenêtres d'ouverture d'un stand");
                }
                effectif = effectifFenetre(fenetre.substring(arobase + 1).trim(), fenetre);
                fenetre = fenetre.substring(0, arobase).trim();
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
            result.add(new FenetreHoraire(
                    heure(debut, "fenetres.heureDebut"),
                    fin.isEmpty() ? null : heure(fin, "fenetres.heureFin"),
                    effectif));
        }
        if (result.isEmpty()) {
            throw new BusinessError.Invalid("fenetres ne contient aucune fenêtre exploitable");
        }
        return result;
    }

    /** The {@code N} of a {@code @N} suffix: a whole number of at least one, as the stand validator requires. */
    private static Integer effectifFenetre(String valeur, String fenetre) {
        try {
            int effectif = Integer.parseInt(valeur);
            if (effectif < 1) {
                throw new BusinessError.Invalid("Fenêtre invalide « " + fenetre + " » : l'effectif doit être au "
                        + "moins 1 — omettez « @N » pour reprendre l'effectif minimum du stand");
            }
            return effectif;
        } catch (NumberFormatException e) {
            throw new BusinessError.Invalid("Fenêtre invalide « " + fenetre + " » : attendu « HH:MM-HH:MM@N » "
                    + "avec N entier, ex. « 14:00-20:00@4 »");
        }
    }

    /**
     * The cap a listing tool applies, {@code defaut} when the caller says
     * nothing.
     *
     * <p>No upper bound is imposed on an explicit value: a tool that silently
     * returned fewer rows than asked would be indistinguishable from one that
     * found fewer. The tools pairing this with a total count are what makes a
     * truncation readable, not a hidden ceiling.</p>
     */
    static int limite(Integer demandee, int defaut) {
        if (demandee == null) {
            return defaut;
        }
        if (demandee <= 0) {
            throw new BusinessError.Invalid("limite : attendu un entier strictement positif, reçu " + demandee);
        }
        return demandee;
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
