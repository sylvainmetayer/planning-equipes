package dev.sylvain.planning.mcp;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strips animateur names out of the human-readable violation lines stored with
 * the last analysis, so the hard-constraint diagnostic can be exposed over MCP
 * without breaking the privacy rule of issue #107.
 *
 * <p>Those lines are produced by {@code ViolationFormatter} for the web UI,
 * where names are wanted: it labels an animateur as {@code "Prénom Nom (id)"}
 * — the only construct of that shape in a violation line, since stands are
 * labelled by their bare name and créneaux by date/heures. Rewriting
 * {@code "Prénom Nom (id)"} into {@code "animateur id"} therefore removes
 * every name while keeping the line actionable (the id is what every other
 * MCP tool takes as input).
 *
 * <p>Deliberately independent of the current referential rather than matching
 * against the known animateur names: a violation may mention an animateur
 * deleted since the analysis ran, and that one must be anonymised too. The
 * worst case of this purely shape-based rewrite is a cosmetically mangled
 * stand label (a stand whose name ends in two words followed by parentheses),
 * never a leaked name.
 */
final class AnonymisationViolations {

    /** Two or more words (letters, accents, hyphens, apostrophes) directly followed by a parenthesised, space-free id. */
    private static final Pattern LIBELLE_ANIMATEUR = Pattern.compile(
            "[\\p{L}][\\p{L}\\p{M}'’\\-]*(?:\\s+[\\p{L}][\\p{L}\\p{M}'’\\-]*)+\\s*\\(([^()\\s]+)\\)");

    private AnonymisationViolations() {
    }

    static List<String> anonymiser(List<String> violations) {
        if (violations == null) {
            return List.of();
        }
        return violations.stream().map(AnonymisationViolations::anonymiser).toList();
    }

    static String anonymiser(String violation) {
        if (violation == null) {
            return null;
        }
        return LIBELLE_ANIMATEUR.matcher(violation)
                .replaceAll(match -> "animateur " + Matcher.quoteReplacement(match.group(1)));
    }
}
