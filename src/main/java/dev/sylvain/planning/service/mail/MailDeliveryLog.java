package dev.sylvain.planning.service.mail;

/**
 * Where {@link MailMetrics} writes the outcome of a send to an animateur. An
 * interface only so that the unit tests building {@code MailMetrics} by hand
 * can do without a database; the application's one implementation is
 * {@link MailDeliveryRepository}.
 */
@FunctionalInterface
public interface MailDeliveryLog {

    /** Records nothing: for a {@code MailMetrics} built by hand, outside the application. */
    MailDeliveryLog NONE = (animateurId, template, outcome) -> {};

    /**
     * Records one send.
     *
     * @param template the template id ({@code mail/rappel-veille}) — what was
     *                 sent, never to whom: the address is not an argument
     */
    void save(String animateurId, String template, MailDeliveryOutcome outcome);
}
