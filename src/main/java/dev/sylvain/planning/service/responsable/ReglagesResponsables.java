package dev.sylvain.planning.service.responsable;

import java.util.Map;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * What the responsables de stand of one edition read: the edition's default
 * and the exception of the stands that have one.
 *
 * @param parDefaut the default of the edition
 * @param parStand  the stands whose setting differs from the default, by stand id; in a request, a
 *                  {@code null} value lifts that stand's exception and a stand left out is untouched
 */
@Schema(requiredProperties = {"parDefaut", "parStand"})
public record ReglagesResponsables(ReglageNoms parDefaut, Map<String, ReglageNoms> parStand) {

    /** The setting that applies to a stand: its own exception, else the edition's. */
    public ReglageNoms forStand(String standId) {
        return parStand.getOrDefault(standId, parDefaut);
    }
}
