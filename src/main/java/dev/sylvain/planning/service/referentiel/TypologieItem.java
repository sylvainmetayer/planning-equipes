package dev.sylvain.planning.service.referentiel;

import java.time.Instant;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * One entry of the typologie referential.
 *
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
        String id, String label, boolean ninja, Integer maxCreneauxParAnimateur, Instant modifieLe) {

    public TypologieItem {
        if (label == null || label.isBlank()) {
            label = id;
        }
    }

    public TypologieItem(String id, String label, boolean ninja, Instant modifieLe) {
        this(id, label, ninja, null, modifieLe);
    }

    public TypologieItem(String id, String label, boolean ninja) {
        this(id, label, ninja, null, null);
    }

    public TypologieItem(String id, String label) {
        this(id, label, false, null, null);
    }

    /** The same item, stamped with the moment the database wrote it. */
    public TypologieItem stamped(Instant modifieLe) {
        return new TypologieItem(id, label, ninja, maxCreneauxParAnimateur, modifieLe);
    }
}
