package dev.sylvain.planning.service.referentiel;

import java.time.Instant;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * One entry of the typologie referential.
 *
 * @param modifieLe when the row was last written (issue #362), echoed back by
 *                  a form on save as its precondition — see
 *                  {@link ConcurrentModificationGuard}; {@code null} on an item
 *                  built in memory or on a write carrying no precondition
 */
@Schema(requiredProperties = {"ninja"})
public record TypologieItem(String id, String label, boolean ninja, Instant modifieLe) {

    public TypologieItem {
        if (label == null || label.isBlank()) {
            label = id;
        }
    }

    public TypologieItem(String id, String label, boolean ninja) {
        this(id, label, ninja, null);
    }

    public TypologieItem(String id, String label) {
        this(id, label, false, null);
    }

    /** The same item, stamped with the moment the database wrote it. */
    public TypologieItem stamped(Instant modifieLe) {
        return new TypologieItem(id, label, ninja, modifieLe);
    }
}
