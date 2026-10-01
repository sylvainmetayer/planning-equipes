package dev.sylvain.planning.service.mail;

import java.time.Instant;

/**
 * One recorded send to an animateur, as {@code envoi_mail} keeps it: who, which
 * mail, what became of it and when — never the address, never the content.
 *
 * @param type     the template's name without its {@code mail/} prefix
 *                 ({@code relance-confirmation})
 * @param category set on a failure only
 */
public record MailDelivery(
        String animateurId,
        String type,
        MailDeliveryOutcome.Status status,
        MailFailureCategory category,
        Instant sentAt) {

    public boolean failed() {
        return status == MailDeliveryOutcome.Status.ECHEC;
    }

    /**
     * The fiche was edited after this send — the organiser's gesture that
     * answers a failure: an address corrected, or the person called and the
     * fiche touched. Any edit counts, by design: nothing of the address is
     * stored to tell which field moved.
     *
     * @param ficheModifiedAt the fiche's {@code modifie_le}, {@code null} when unknown
     */
    public boolean ficheModifiedSince(Instant ficheModifiedAt) {
        return ficheModifiedAt != null && sentAt != null && ficheModifiedAt.isAfter(sentAt);
    }

    /**
     * This send failed and nothing has been done about it since: what the
     * Animateurs page shows as « échec d'envoi » rather than as a silence.
     */
    public boolean failingFor(Instant ficheModifiedAt) {
        return failed() && !ficheModifiedSince(ficheModifiedAt);
    }

    /**
     * The relay refused this very address, and the fiche has not been edited
     * since: a reminder would be written to the same refusal, so neither the
     * hand nor the night sends one.
     */
    public boolean refusedAddressStands(Instant ficheModifiedAt) {
        return failingFor(ficheModifiedAt) && category == MailFailureCategory.ADRESSE_REFUSEE;
    }
}
