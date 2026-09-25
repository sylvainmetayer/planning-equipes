package dev.sylvain.planning.service.referentiel;

import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.IdGenerator;
import java.util.regex.Pattern;

/**
 * The readable code a stand, a typologie or an emplacement may carry next to
 * its generated id (ADR 0050): what the files an organiser keeps name it by.
 *
 * <p>Optional, and unique in its edition — the unique index is the last word,
 * {@link #refuseTaken} the sentence a user reads instead of a constraint
 * violation.</p>
 */
public final class Codes {

    /** The column width. */
    static final int MAX_LENGTH = 64;

    /**
     * What a CSV cell listing several values splits on ({@code |}, {@code ;},
     * {@code ,}, a line break): a code carrying one could not be cited from a
     * stand's {@code typologies} column.
     */
    private static final Pattern FORBIDDEN = Pattern.compile("[|;,\\r\\n]");

    private Codes() {}

    /**
     * The code as stored: trimmed, {@code null} when blank. Refuses one no file
     * could cite, and one shaped like an id of its own referential — {@code S4}
     * for a stand: « an id or a code » must never be ambiguous, and V100 keeps
     * the two apart for the rows it renumbered.
     */
    public static String normalise(String code, IdGenerator.Kind kind) {
        String normalise = normalise(code);
        if (normalise != null && kind.hasGeneratedShape(normalise)) {
            throw new BusinessError.Invalid("Le code « " + normalise + " » a la forme d'un identifiant ("
                    + kind.prefix() + " suivi d'un nombre), que l'application attribue elle-même : "
                    + "choisissez-en un autre.");
        }
        return normalise;
    }

    /** The code as stored: trimmed, {@code null} when blank. Refuses one no file could cite. */
    static String normalise(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String trimmed = code.trim();
        if (trimmed.length() > MAX_LENGTH) {
            throw new BusinessError.Invalid(
                    "Le code « " + trimmed + " » est trop long : " + MAX_LENGTH + " caractères au plus.");
        }
        if (FORBIDDEN.matcher(trimmed).find()) {
            throw new BusinessError.Invalid("Le code « " + trimmed
                    + " » ne peut contenir ni virgule, ni point-virgule, ni barre verticale, ni saut de ligne : "
                    + "ce sont les séparateurs des fichiers d'import.");
        }
        return trimmed;
    }

    /**
     * Refuses {@code code} when another row already carries it.
     *
     * @param holder the id of the row carrying the code now, {@code null} when none does
     * @param id     the id of the row being written, {@code null} on a creation
     */
    static void refuseTaken(String code, String holder, String id, String what) {
        if (code != null && holder != null && !holder.equals(id)) {
            throw new BusinessError.Conflict(
                    "Le code « " + code + " » est déjà porté par " + what + " " + holder + " dans cette édition.");
        }
    }
}
