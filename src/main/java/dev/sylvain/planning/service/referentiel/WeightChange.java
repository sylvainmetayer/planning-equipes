package dev.sylvain.planning.service.referentiel;

import dev.sylvain.planning.service.journal.Acteur;
import java.time.Instant;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * One change of a constraint's effective weight or activation in an edition —
 * values included, which the action journal deliberately never keeps. Nothing
 * nominative: a rule, integers, booleans, a typed origin, a date.
 *
 * @param name          the constraint; kept readable after the rule leaves the catalogue
 * @param weightBefore  the effective weight before, deployment default included;
 *                      {@code null} when the line is about the activation only
 * @param weightAfter   the effective weight after, same convention
 * @param backToDefault the edition dropped its own weight: « 20 → défaut (1) »
 * @param activeBefore  the effective state before; {@code null} when the line
 *                      is about the weight only
 * @param activeAfter   the effective state after, same convention
 * @param sourceEdition for {@link WeightChangeOrigin#DUPLICATION}, the edition
 *                      the dosage was inherited from
 */
@Schema(requiredProperties = {"id", "name", "backToDefault", "origin", "actor", "createdAt"})
public record WeightChange(
        long id,
        String name,
        Integer weightBefore,
        Integer weightAfter,
        boolean backToDefault,
        Boolean activeBefore,
        Boolean activeAfter,
        WeightChangeOrigin origin,
        Acteur actor,
        String sourceEdition,
        Instant createdAt) {

    /** A line about to be written: no id nor date yet, the database gives both. */
    static WeightChange of(
            String name,
            Integer weightBefore,
            Integer weightAfter,
            boolean backToDefault,
            Boolean activeBefore,
            Boolean activeAfter,
            WeightChangeOrigin origin,
            String sourceEdition) {
        return new WeightChange(
                0,
                name,
                weightBefore,
                weightAfter,
                backToDefault,
                activeBefore,
                activeAfter,
                origin,
                origin.acteur(),
                sourceEdition,
                null);
    }
}
