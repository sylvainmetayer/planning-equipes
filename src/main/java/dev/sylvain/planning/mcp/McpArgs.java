package dev.sylvain.planning.mcp;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
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

    private McpArgs() {
    }

    static LocalDate date(String value, String champ) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(champ + " : date invalide « " + value + " », format attendu AAAA-MM-JJ");
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
            throw new IllegalArgumentException(champ + " : heure invalide « " + value + " », format attendu HH:MM");
        }
    }

    static <E extends Enum<E>> E enumeration(Class<E> type, String value, String champ) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Arrays.stream(type.getEnumConstants())
                .filter(constant -> constant.name().equalsIgnoreCase(value.trim()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(champ + " : valeur inconnue « " + value
                        + " », valeurs possibles : "
                        + Arrays.stream(type.getEnumConstants()).map(Enum::name).collect(Collectors.joining(", "))));
    }
}
