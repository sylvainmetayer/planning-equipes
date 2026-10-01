package dev.sylvain.planning.service.mail;

/**
 * What became of one send: it left, or it failed and why.
 *
 * <p>« Left » means handed to the relay — the relay accepting a mail is not
 * the recipient reading it, nor even receiving it, and with
 * {@code MAIL_MOCK=true} nothing leaves at all: the screens word that state
 * « simulé » there.</p>
 *
 * @param category set on a failure only
 */
public record MailDeliveryOutcome(Status status, MailFailureCategory category) {

    /** The two states a recorded send can be in; a missing address records nothing. */
    public enum Status {
        ENVOYE,
        ECHEC
    }

    /** A mail the relay accepted. */
    public static final MailDeliveryOutcome SENT = new MailDeliveryOutcome(Status.ENVOYE, null);

    /** A mail that could not leave, classified from what was thrown. */
    public static MailDeliveryOutcome failed(Throwable failure) {
        return new MailDeliveryOutcome(Status.ECHEC, MailFailureCategory.of(failure));
    }

    public boolean wasSent() {
        return status == Status.ENVOYE;
    }
}
