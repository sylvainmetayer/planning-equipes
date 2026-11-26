package dev.sylvain.planning.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * The white-label and legal-notice settings, grouped (4.0): fourteen
 * {@code BRANDING_*} and twelve {@code LEGAL_*} variables became eight, four of
 * them grouped, each holding {@code key=value} pairs separated by {@code ;} (a
 * literal semicolon in a value is written {@code \;}).
 *
 * <p>The configuration properties the application reads ({@code planning.legal.*},
 * {@code planning.branding.*}) did not move: {@link GroupedVariablesInterceptor}
 * answers them from the group first, then from the old variable, which is still
 * read, with a warning, for the whole 4.x ({@link RenamedVariables}) and
 * dropped in 5.0. This class is the single table of that mapping.</p>
 */
final class GroupedVariables {

    /** One property, the grouped variable and key that now carry it, the old variable that did. */
    record Entry(String property, String group, String key, String legacy) {}

    static final String MENTIONS = "LEGAL_MENTIONS";
    static final String ACCESSIBILITE = "LEGAL_ACCESSIBILITE";
    static final String IMAGES = "BRANDING_IMAGES";
    static final String COLORS = "BRANDING_COLORS";

    static final List<Entry> ENTRIES = List.of(
            new Entry("planning.legal.editeur", MENTIONS, "editeur", "LEGAL_EDITEUR"),
            new Entry(
                    "planning.legal.directeur-publication",
                    MENTIONS,
                    "directeur-publication",
                    "LEGAL_DIRECTEUR_PUBLICATION"),
            new Entry("planning.legal.hebergeur", MENTIONS, "hebergeur", "LEGAL_HEBERGEUR"),
            new Entry("planning.legal.contact", MENTIONS, "contact", "LEGAL_CONTACT"),
            new Entry(
                    "planning.legal.responsable-traitement",
                    MENTIONS,
                    "responsable-traitement",
                    "LEGAL_RESPONSABLE_TRAITEMENT"),
            new Entry("planning.legal.donnees.base-legale", MENTIONS, "base-legale", "LEGAL_BASE_LEGALE"),
            new Entry("planning.legal.donnees.conservation", MENTIONS, "conservation", "LEGAL_CONSERVATION"),
            new Entry("planning.legal.accessibilite.etat", ACCESSIBILITE, "etat", "LEGAL_ACCESSIBILITE_ETAT"),
            new Entry(
                    "planning.legal.accessibilite.date-audit",
                    ACCESSIBILITE,
                    "date-audit",
                    "LEGAL_ACCESSIBILITE_DATE_AUDIT"),
            new Entry(
                    "planning.legal.accessibilite.contenus-non-accessibles",
                    ACCESSIBILITE,
                    "contenus",
                    "LEGAL_ACCESSIBILITE_CONTENUS"),
            new Entry(
                    "planning.legal.accessibilite.signalement",
                    ACCESSIBILITE,
                    "signalement",
                    "LEGAL_ACCESSIBILITE_SIGNALEMENT"),
            new Entry("planning.branding.logo-url", IMAGES, "logo", "BRANDING_LOGO_URL"),
            new Entry("planning.branding.mascot-url", IMAGES, "mascot", "BRANDING_MASCOT_URL"),
            new Entry("planning.branding.mascot-icon-url", IMAGES, "mascot-icon", "BRANDING_MASCOT_ICON_URL"),
            new Entry("planning.branding.pdf.logo", IMAGES, "pdf-logo", "BRANDING_PDF_LOGO"),
            new Entry("planning.branding.pdf.strip", IMAGES, "pdf-strip", "BRANDING_PDF_STRIP"),
            new Entry("planning.branding.accent-color", COLORS, "accent", "BRANDING_ACCENT_COLOR"),
            new Entry("planning.branding.pdf.palette.headline", COLORS, "pdf-headline", "BRANDING_PDF_HEADLINE"),
            new Entry("planning.branding.pdf.palette.muted", COLORS, "pdf-muted", "BRANDING_PDF_MUTED"),
            new Entry("planning.branding.pdf.palette.accent", COLORS, "pdf-accent", "BRANDING_PDF_ACCENT"),
            new Entry("planning.branding.pdf.palette.highlight", COLORS, "pdf-highlight", "BRANDING_PDF_HIGHLIGHT"),
            new Entry("planning.branding.pdf.palette.pill", COLORS, "pdf-pill", "BRANDING_PDF_PILL"));

    private static final Pattern UNESCAPED_SEMICOLON = Pattern.compile("(?<!\\\\);");

    /**
     * What a key looks like. Text that does not is more likely a piece of a
     * value cut by an unescaped {@code ;}, and a warning never prints a value.
     */
    private static final Pattern KEY_SHAPE = Pattern.compile("[a-z0-9-]{1,40}");

    private GroupedVariables() {}

    /**
     * The pairs of one grouped value. A segment without {@code =} is kept under
     * its whole text with a {@code null} value, so the caller can name it.
     */
    static Map<String, String> parse(String raw) {
        Map<String, String> pairs = new LinkedHashMap<>();
        if (raw == null) {
            return pairs;
        }
        for (String segment : UNESCAPED_SEMICOLON.split(raw)) {
            if (segment.isBlank()) {
                continue;
            }
            int eq = segment.indexOf('=');
            if (eq < 0) {
                pairs.put(segment.strip(), null);
            } else {
                pairs.put(
                        segment.substring(0, eq).strip(),
                        segment.substring(eq + 1).strip().replace("\\;", ";"));
            }
        }
        return pairs;
    }

    /**
     * The value {@code property} takes from the grouped variable, else from the
     * old variable, else {@code null} to let the usual sources answer.
     */
    static String resolve(String property, UnaryOperator<String> env) {
        for (Entry e : ENTRIES) {
            if (e.property().equals(property)) {
                String grouped = parse(env.apply(e.group())).get(e.key());
                if (grouped != null && !grouped.isBlank()) {
                    return grouped;
                }
                String old = env.apply(e.legacy());
                return old == null || old.isBlank() ? null : old;
            }
        }
        return null;
    }

    /** Boot warnings: old names still read, unknown keys, segments without {@code =}. Never a value. */
    static List<String> warnings(UnaryOperator<String> env) {
        List<String> lines = new ArrayList<>();
        for (Entry e : ENTRIES) {
            String old = env.apply(e.legacy());
            if (old != null && !old.isBlank()) {
                lines.add("La variable " + e.legacy() + " est renommée : posez la clé " + e.key() + " de "
                        + e.group() + ". L'ancien nom est lu pendant toute la 4.x et retiré en 5.0 "
                        + "(site/src/docs/installer/passer-a-la-4-0.md).");
            }
        }
        for (String group : List.of(MENTIONS, ACCESSIBILITE, IMAGES, COLORS)) {
            parse(env.apply(group)).forEach((key, value) -> {
                if (value == null) {
                    // The segment's text is not printed: it is most often the tail of a value.
                    lines.add(group + " : un segment n'est pas de la forme clé=valeur, ignoré "
                            + "(un point-virgule dans une valeur s'écrit \\;).");
                } else if (ENTRIES.stream()
                        .noneMatch(e -> e.group().equals(group) && e.key().equals(key))) {
                    lines.add(group + " : "
                            + (KEY_SHAPE.matcher(key).matches() ? "la clé « " + key + " »" : "une clé")
                            + " n'existe pas, ignorée.");
                }
            });
        }
        return lines;
    }
}
