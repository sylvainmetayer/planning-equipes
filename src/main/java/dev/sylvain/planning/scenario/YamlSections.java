package dev.sylvain.planning.scenario;

import java.util.List;
import java.util.Map;

/**
 * Typed reads over the {@code Map}/{@code List}/scalar object graph SnakeYAML
 * hands back.
 *
 * <p>SnakeYAML has no generic type to check a section against, so getting a
 * {@code Map<String, Object>} or a {@code List<Map<String, Object>>} out of it
 * <b>requires</b> an unchecked cast — this is the rare case where the warning
 * is unavoidable rather than sloppy. The point of this class is that the cast
 * is written <b>four times, here</b>, each with the reason above and each
 * turning a wrongly-shaped section into a message naming the offending key,
 * instead of being suppressed nine times across the parsing methods where
 * nothing said which of them was justified.</p>
 *
 * <p>Every reader returns {@code null} for an absent section, on purpose: the
 * scenario format distinguishes "the key is not there" from "the key is there
 * and empty" (a file with no {@code postes:} has its seats generated, a file
 * with an empty one has none), and the call sites branch on exactly that.</p>
 */
public final class YamlSections {

    private YamlSections() {
    }

    /** A nested mapping, e.g. {@code parametresLegaux:}. */
    @SuppressWarnings("unchecked") // SnakeYAML returns Object; see the class javadoc
    public static Map<String, Object> objet(Map<String, Object> parent, String key) {
        Object valeur = parent.get(key);
        if (valeur == null) {
            return null;
        }
        requireType(valeur, Map.class, key, "un bloc de champs");
        return (Map<String, Object>) valeur;
    }

    /** A list of mappings, e.g. {@code stands:} or {@code horaires:}. */
    @SuppressWarnings("unchecked") // SnakeYAML returns Object; see the class javadoc
    public static List<Map<String, Object>> objets(Map<String, Object> parent, String key) {
        Object valeur = parent.get(key);
        if (valeur == null) {
            return null;
        }
        requireType(valeur, List.class, key, "une liste");
        return (List<Map<String, Object>>) valeur;
    }

    /** A list of text scalars, e.g. {@code typologiesProposees:}. */
    @SuppressWarnings("unchecked") // SnakeYAML returns Object; see the class javadoc
    public static List<String> chaines(Map<String, Object> parent, String key) {
        Object valeur = parent.get(key);
        if (valeur == null) {
            return null;
        }
        requireType(valeur, List.class, key, "une liste");
        return (List<String>) valeur;
    }

    /**
     * A list of scalars whose YAML type is not pinned down — dates that a
     * quoted/unquoted file may hand back as {@code String} or as
     * {@code java.util.Date}, typologie ids, … The caller narrows each element.
     */
    @SuppressWarnings("unchecked") // SnakeYAML returns Object; see the class javadoc
    public static List<Object> valeurs(Map<String, Object> parent, String key) {
        Object valeur = parent.get(key);
        if (valeur == null) {
            return null;
        }
        requireType(valeur, List.class, key, "une liste");
        return (List<Object>) valeur;
    }

    /**
     * Fails with the key name and the shape expected. Without this, a
     * mistyped section surfaces as a bare {@code ClassCastException} raised
     * somewhere down the parsing, whose message names two Java classes and not
     * the line the author has to fix.
     */
    private static void requireType(Object valeur, Class<?> attendu, String key, String forme) {
        if (!attendu.isInstance(valeur)) {
            throw new IllegalArgumentException(
                    "La section « " + key + " » doit être " + forme + " dans le fichier de scénario.");
        }
    }
}
