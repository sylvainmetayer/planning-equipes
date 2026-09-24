package dev.sylvain.planning.service.mail;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The one place a mail leaves the application, and so the one place it is
 * counted: {@code planning_mail_sent_total} and
 * {@code planning_mail_failures_total}, labelled by the template that wrote
 * it (see {@code docs/observabilite.md} § Métriques).
 *
 * <p>Counting says nothing about the failure policy, which stays with the
 * caller: a failure is counted and <b>rethrown</b>, so
 * {@code MailService} still propagates it and the notification dispatcher
 * still swallows it. The label is the template's name — a closed set, the
 * files under {@code resources/templates/mail/} — never a recipient.</p>
 *
 * <p>A mocked mailer ({@code MAIL_MOCK=true}) counts too: in a staging
 * environment, "the mail would have left" is what one wants to see.</p>
 */
@ApplicationScoped
public class MailMetrics {

    static final String SENT = "planning.mail.sent";
    static final String FAILURES = "planning.mail.failures";

    private static final String TEMPLATE_PREFIX = "mail/";

    private final MeterRegistry registry;

    /** Also built by hand in the unit tests, over a {@code SimpleMeterRegistry}. */
    @Inject
    public MailMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * Sends {@code mail} and counts the outcome.
     *
     * @param template the template id ({@code mail/planning-publie}) the mail was
     *                 rendered from
     */
    public void send(Mailer mailer, String template, Mail mail) {
        try {
            mailer.send(mail);
        } catch (RuntimeException e) {
            counter(FAILURES, "Mails the SMTP server refused or could not be reached for", template)
                    .increment();
            throw e;
        }
        counter(SENT, "Mails handed to the SMTP server", template).increment();
    }

    private Counter counter(String name, String description, String template) {
        return Counter.builder(name)
                .description(description)
                .tag("template", label(template))
                .register(registry);
    }

    static String label(String template) {
        return template.startsWith(TEMPLATE_PREFIX) ? template.substring(TEMPLATE_PREFIX.length()) : template;
    }
}
