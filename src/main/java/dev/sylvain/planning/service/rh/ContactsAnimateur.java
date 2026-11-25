package dev.sylvain.planning.service.rh;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * The two people to call about an animateur: whoever to warn in an emergency, and
 * — for a minor — their legal representative. Every field is optional.
 *
 * <p>A third party's data, given by the animateur or their representative:
 * <b>never</b> part of {@code Animateur}, of the scenario file, of an export, of
 * the MCP tools or of a colleague's espace. Only the administrators and the RH
 * right read it, and the animateur reads and writes their own.</p>
 */
@Schema(name = "ContactsAnimateur")
public record ContactsAnimateur(
        String urgenceNom, String urgenceTelephone, String representantNom, String representantTelephone) {

    public static final ContactsAnimateur VIDE = new ContactsAnimateur(null, null, null, null);

    /** Nothing entered: the row is deleted rather than kept empty. */
    @JsonIgnore
    public boolean isEmpty() {
        return urgenceNom == null
                && urgenceTelephone == null
                && representantNom == null
                && representantTelephone == null;
    }

    /** Whether a legal representative is known: a name or a number. */
    public boolean hasRepresentative() {
        return representantNom != null || representantTelephone != null;
    }
}
