package dev.sylvain.planning.service.mail;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.logging.Log;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The one place a mail leaves the application, and so the one place it is
 * counted: {@code planning_mail_sent_total} and
 * {@code planning_mail_failures_total}, labelled by the template that wrote
 * it (see {@code docs/observabilite.md} § Métriques) — and, for a mail to an
 * animateur, the one place its outcome is <b>recorded</b> against the person
 * ({@link MailDeliveryRepository}): sent, or failed with its
 * {@link MailFailureCategory}.
 *
 * <p>Counting and recording say nothing about the failure policy, which stays
 * with the caller: a failure is counted, recorded and <b>rethrown</b>, so
 * {@code MailService} still propagates it and the notification dispatcher
 * still swallows it. The label is the template's name — a closed set, the
 * files under {@code resources/templates/mail/} — never a recipient.</p>
 *
 * <p>Recording is best-effort in both directions: a database that refuses the
 * row is logged and changes nothing, neither a send that left nor the
 * exception of one that did not. A caller must never believe a mail failed
 * because its trace could not be written.</p>
 *
 * <p>A mocked mailer ({@code MAIL_MOCK=true}) counts and records too: in a
 * staging environment, "the mail would have left" is what one wants to see —
 * which is why the screens word that state « simulé » there.</p>
 */
@ApplicationScoped
public class MailMetrics {

    static final String SENT = "planning.mail.sent";
    static final String FAILURES = "planning.mail.failures";

    private static final String TEMPLATE_PREFIX = "mail/";

    private final MeterRegistry registry;

    private final MailDeliveryLog deliveries;

    @Inject
    public MailMetrics(MeterRegistry registry, MailDeliveryLog deliveries) {
        this.registry = registry;
        this.deliveries = deliveries;
    }

    /** Built by hand in the unit tests, over a {@code SimpleMeterRegistry} and without a database. */
    public MailMetrics(MeterRegistry registry) {
        this(registry, MailDeliveryLog.NONE);
    }

    /**
     * Sends a mail meant for nobody in the referential — the admin's — and
     * counts the outcome.
     *
     * @param template the template id ({@code mail/planning-publie}) the mail was
     *                 rendered from
     */
    public void send(Mailer mailer, String template, Mail mail) {
        send(mailer, template, mail, null);
    }

    /**
     * Sends {@code mail}, counts the outcome and, when it is meant for an
     * animateur, records it against them.
     *
     * @param template    the template id ({@code mail/code-acces}) the mail was
     *                    rendered from
     * @param animateurId the animateur it is written to, {@code null} for a
     *                    mail to the admin — which records nothing
     */
    public void send(Mailer mailer, String template, Mail mail, String animateurId) {
        try {
            mailer.send(mail);
        } catch (RuntimeException e) {
            counter(FAILURES, "Mails the SMTP server refused or could not be reached for", template)
                    .increment();
            saveOutcome(animateurId, template, MailDeliveryOutcome.failed(e));
            throw e;
        }
        counter(SENT, "Mails handed to the SMTP server", template).increment();
        saveOutcome(animateurId, template, MailDeliveryOutcome.SENT);
    }

    private void saveOutcome(String animateurId, String template, MailDeliveryOutcome outcome) {
        if (animateurId == null) {
            return;
        }
        try {
            deliveries.save(animateurId, template, outcome);
        } catch (RuntimeException e) {
            Log.errorf(
                    e, "The outcome of a %s mail to animateur %s could not be recorded", label(template), animateurId);
        }
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
