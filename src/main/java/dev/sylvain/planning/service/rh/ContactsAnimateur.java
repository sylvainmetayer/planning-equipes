package dev.sylvain.planning.service.rh;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Instant;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * The two people to call about an animateur: whoever to warn in an emergency, and
 * — for a minor — their legal representative. Every field is optional.
 *
 * <p>A third party's data, given by the animateur or their representative:
 * <b>never</b> part of {@code Animateur}, of the scenario file, of an export, of
 * the MCP tools or of a colleague's espace. Only the administrators and the RH
 * right read it, and the animateur reads and writes their own.</p>
 *
 * @param modifieLe when the stored row was last written, {@code null} when there is none: sent back with a write,
 *     it is that write's precondition — two people edit these (the animateur and the organisation), and the
 *     second save is told rather than silently overwriting the first
 */
@Schema(name = "ContactsAnimateur")
public record ContactsAnimateur(
        String urgenceNom,
        String urgenceTelephone,
        String representantNom,
        String representantTelephone,
        Instant modifieLe) {

    public static final ContactsAnimateur VIDE = new ContactsAnimateur(null, null, null, null, null);

    /** The same values, read as of {@code stamp}. */
    public ContactsAnimateur stamped(Instant stamp) {
        return new ContactsAnimateur(urgenceNom, urgenceTelephone, representantNom, representantTelephone, stamp);
    }

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
