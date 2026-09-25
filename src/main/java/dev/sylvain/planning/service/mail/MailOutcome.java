package dev.sylvain.planning.service.mail;

/**
 * What became of one mail to an animateur, as far as this server can know.
 *
 * <p>{@link #ENVOYE} means <b>accepted by the relay</b>, never « received »: a
 * rejection the recipient's server sends back later does not reach here, and
 * with {@code MAIL_MOCK} nothing leaves at all. The screens therefore never
 * show it as a proof of delivery; only a failure is worth a word. The values
 * are those of the publication trace, so the two read the same.</p>
 */
public enum MailOutcome {
    ENVOYE,
    SANS_EMAIL,
    ECHEC
}
