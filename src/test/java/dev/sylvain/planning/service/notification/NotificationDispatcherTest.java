package dev.sylvain.planning.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import dev.sylvain.planning.domain.DemandeEchange;
import dev.sylvain.planning.service.ProductName;
import dev.sylvain.planning.service.espace.ApplicationLinks;
import dev.sylvain.planning.service.mail.MailMetrics;
import dev.sylvain.planning.service.mail.MailTemplates;
import dev.sylvain.planning.service.publication.AdminAddress;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.mailer.Mail;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The delivery policy of the notifications, now written in a single place. It
 * used to be written by hand on every call — a
 * {@code try/catch (RuntimeException)} around every send, in five services —
 * and nothing stopped anyone from forgetting one.
 */
class NotificationDispatcherTest {

    private final List<Mail> envoyes = new ArrayList<>();
    private final NotificationWriter redacteur = new NotificationWriter(
            new AdminAddress(Optional.of("admin@example.org")),
            new ApplicationLinks(Optional.of("https://planning.example.org")),
            ProductName.neutral(),
            MailTemplates.standalone(ProductName.neutral()));
    private NotificationDispatcher expediteur;
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @BeforeEach
    void buildDispatcher() {
        expediteur = new NotificationDispatcher(
                mails -> envoyes.addAll(List.of(mails)),
                redacteur,
                MailTemplates.standalone(ProductName.neutral()),
                new MailMetrics(registry));
    }

    private static Notification oneSubmission() {
        DemandeEchange demande = new DemandeEchange();
        demande.setId("D1");
        demande.setPrevalidationOk(true);
        return new Notification.DemandesSoumises("Alice Dupont", List.of(demande));
    }

    @Test
    void uneNotificationRedigeeEstEnvoyeeAuDestinataireEcrit() {
        expediteur.surNotification(oneSubmission());

        assertThat(envoyes).singleElement().satisfies(mail -> {
            assertThat(mail.getTo()).containsExactly("admin@example.org");
            assertThat(mail.getSubject()).contains("nouvelle demande d'échange");
        });
    }

    /** Nobody to notify: nothing leaves, and above all nothing fails. */
    @Test
    void uneNotificationSansDestinataireNEnvoieRien() {
        expediteur.surNotification(new Notification.DemandesSoumises("Alice Dupont", List.of()));

        assertThat(envoyes).isEmpty();
    }

    /**
     * The central invariant: a broken SMTP must never fail the business
     * operation the notification describes — that operation already happened.
     */
    @Test
    void aFailedSendIsSwallowedWithoutBreakingTheOperation() {
        expediteur = new NotificationDispatcher(
                mails -> {
                    throw new IllegalStateException("SMTP down");
                },
                redacteur,
                MailTemplates.standalone(ProductName.neutral()),
                new MailMetrics(registry));

        assertThatCode(() -> expediteur.surNotification(oneSubmission())).doesNotThrowAnyException();
    }

    /** Swallowed is not uncounted: the failure shows on the metrics, under the notification's template. */
    @Test
    void aSwallowedFailureIsStillCounted() {
        expediteur = new NotificationDispatcher(
                mails -> {
                    throw new IllegalStateException("SMTP down");
                },
                redacteur,
                MailTemplates.standalone(ProductName.neutral()),
                new MailMetrics(registry));

        expediteur.surNotification(oneSubmission());

        assertThat(registry.get("planning.mail.failures")
                        .tag("template", "demandes-soumises")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }

    /** The {@code catch} covers the writing as much as the sending. */
    @Test
    void aFailedWritingIsSwallowedToo() {
        NotificationWriter failing = new NotificationWriter(null, null, null, null) {
            @Override
            public Optional<MailDraft> rediger(Notification notification) {
                throw new IllegalStateException("rédaction impossible");
            }
        };
        expediteur = new NotificationDispatcher(
                mails -> envoyes.addAll(List.of(mails)),
                failing,
                MailTemplates.standalone(ProductName.neutral()),
                new MailMetrics(registry));

        assertThatCode(() -> expediteur.surNotification(oneSubmission())).doesNotThrowAnyException();
        assertThat(envoyes).isEmpty();
    }

    /**
     * A covoiturage decided while the SMTP relay is down: the decision stands,
     * the admin's call returns, and the lost mail is counted under its template.
     */
    @Test
    void aCarpoolDecisionSurvivesAFailedMail() {
        expediteur = new NotificationDispatcher(
                mails -> {
                    throw new IllegalStateException("SMTP down");
                },
                redacteur,
                MailTemplates.standalone(ProductName.neutral()),
                new MailMetrics(registry));

        assertThatCode(() -> {
                    expediteur.surNotification(
                            new Notification.CarpoolValidated("bob@example.org", "Bob", List.of("Alice"), null));
                    expediteur.surNotification(
                            new Notification.CarpoolSetAside("alice@example.org", "Alice", "complet", null));
                    expediteur.surNotification(new Notification.CarpoolCancelled(
                            "alice@example.org", "Alice", List.of("Bob"), "panne", true, null));
                })
                .doesNotThrowAnyException();
        assertThat(registry.get("planning.mail.failures")
                        .tag("template", "covoiturage-valide")
                        .counter()
                        .count())
                .isEqualTo(1.0);
        assertThat(registry.get("planning.mail.failures")
                        .tag("template", "covoiturage-annule")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }
}
