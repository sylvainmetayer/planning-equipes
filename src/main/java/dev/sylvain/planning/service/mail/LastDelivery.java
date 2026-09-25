package dev.sylvain.planning.service.mail;

import java.time.Instant;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * The last mail sent to one animateur since their fiche last changed: the
 * {@code dernierEnvoi} of their acknowledgement line.
 *
 * @param type           a {@link MailKind} name
 * @param statut         a {@link MailOutcome} name — {@code ENVOYE} means
 *                       accepted by the relay, never « received »
 * @param categorieEchec a {@link MailFailure} name, {@code null} unless the
 *                       send failed
 * @param envoyeLe       when it was attempted
 */
@Schema(requiredProperties = {"type", "statut", "envoyeLe"})
public record LastDelivery(
        @Schema(type = SchemaType.STRING, implementation = MailKind.class)
        String type,

        @Schema(type = SchemaType.STRING, implementation = MailOutcome.class)
        String statut,

        @Schema(type = SchemaType.STRING, implementation = MailFailure.class, nullable = true)
        String categorieEchec,

        Instant envoyeLe) {

    /** A failure worth an organiser's attention: the last send to this fiche did not leave. */
    public boolean failed() {
        return MailOutcome.ECHEC.name().equals(statut);
    }

    /**
     * The relay refused this address for good: writing to it again before the
     * fiche changes would earn the same refusal. A temporary failure does not
     * count — the next reminder is allowed.
     */
    public boolean blocksReminder() {
        return failed() && MailFailure.ADRESSE_REFUSEE.name().equals(categorieEchec);
    }
}
