package dev.sylvain.planning.service.referentiel;

import java.time.Instant;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * One entry of the typologie referential.
 *
 * @param description free note the organiser writes for themselves — « cette
 *                  typologie nécessite d'apprendre 45 jeux ». Read on the
 *                  Typologies screen and in the planning-by-typologie view,
 *                  nowhere else: not on a PDF, not in an animateur's espace.
 *                  {@code null} until somebody writes one
 * @param maxCreneauxParAnimateur how many créneaux one animateur may hold on
 *                  this typologie over the whole edition; {@code null} means no
 *                  cap, which is what every typologie carries until somebody
 *                  sets one (issue #594, ADR 0042)
 * @param modifieLe when the row was last written (issue #362), echoed back by
 *                  a form on save as its precondition — see
 *                  {@link ConcurrentModificationGuard}; {@code null} on an item
 *                  built in memory or on a write carrying no precondition
 */
@Schema(requiredProperties = {"ninja"})
public record TypologieItem(
        String id,
        String label,
        boolean ninja,
        Integer maxCreneauxParAnimateur,
        String description,
        Instant modifieLe) {

    public TypologieItem {
        if (label == null || label.isBlank()) {
            label = id;
        }
        // A blank note and no note are the same thing; storing the difference
        // would make the screens test for both.
        if (description != null && description.isBlank()) {
            description = null;
        }
    }

    /**
     * No convenience overload stops one field short of the canonical one. There
     * used to be one carrying the {@code modifieLe} without the cap, and
     * {@code TypologieService} used it on every write: the cap a form sent was
     * silently dropped on its way to the database, and the response came back
     * without it. The same overload minus the description would drop an
     * organiser's note the same way, so a caller that writes a whole row writes
     * every field of it — the two below are for fixtures, and say so by being
     * short enough that nobody mistakes them for complete.
     */
    public TypologieItem(String id, String label, boolean ninja) {
        this(id, label, ninja, null, null, null);
    }

    public TypologieItem(String id, String label) {
        this(id, label, false, null, null, null);
    }

    /** The same item, stamped with the moment the database wrote it. */
    public TypologieItem stamped(Instant modifieLe) {
        return new TypologieItem(id, label, ninja, maxCreneauxParAnimateur, description, modifieLe);
    }
}
