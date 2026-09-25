package dev.sylvain.planning.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import dev.sylvain.planning.domain.DemandeEchange;
import dev.sylvain.planning.service.ProductName;
import dev.sylvain.planning.service.espace.ApplicationLinks;
import dev.sylvain.planning.service.mail.MailDeliveryLog;
import dev.sylvain.planning.service.mail.MailKind;
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
    /** Never reached: every notification sent here goes to the admin, not to an animateur. */
    private final MailDeliveryLog deliveries = null;

    private NotificationDispatcher expediteur;
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @BeforeEach
    void buildDispatcher() {
        expediteur = new NotificationDispatcher(
                mails -> envoyes.addAll(List.of(mails)),
                redacteur,
                MailTemplates.standalone(ProductName.neutral()),
                new MailMetrics(registry),
                deliveries);
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
                new MailMetrics(registry),
                deliveries);

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
                new MailMetrics(registry),
                deliveries);

        expediteur.surNotification(oneSubmission());

        assertThat(registry.get("planning.mail.failures")
                        .tag("template", "demandes-soumises")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }

    /**
     * A failed mail to an animateur is both counted and journalled, and
     * still swallowed: neither record moves the one {@code catch}.
     */
    @Test
    void aFailedMailToAnAnimateurIsCountedAndJournalled() {
        List<String> journal = new ArrayList<>();
        MailDeliveryLog recording = new MailDeliveryLog(null) {
            @Override
            public void recordFailure(String animateurId, MailKind kind, Throwable failure) {
                journal.add("ECHEC " + animateurId + " " + kind);
            }

            @Override
            public void recordSent(String animateurId, MailKind kind) {
                journal.add("ENVOYE " + animateurId + " " + kind);
            }

            @Override
            public boolean isAddressBlocked(String animateurId) {
                return false;
            }
        };
        expediteur = new NotificationDispatcher(
                mails -> {
                    throw new IllegalStateException("SMTP down");
                },
                redacteur,
                MailTemplates.standalone(ProductName.neutral()),
                new MailMetrics(registry),
                recording);

        assertThatCode(() -> expediteur.surNotification(new Notification.RelanceConfirmation(
                        "A1", "a1@example.org", "Alice", "https://planning.example.org/animateur/t")))
                .doesNotThrowAnyException();

        assertThat(journal).containsExactly("ECHEC A1 RELANCE_NUIT");
        assertThat(registry.find("planning.mail.failures").counters().stream()
                        .mapToDouble(c -> c.count())
                        .sum())
                .isEqualTo(1.0);
    }

    /**
     * An address the relay refused for good: nothing is attempted, nothing is
     * journalled — the last line stays the refusal — and nothing fails.
     */
    @Test
    void aMailToABlockedAddressIsSkippedWithoutALine() {
        List<String> journal = new ArrayList<>();
        MailDeliveryLog blocking = new MailDeliveryLog(null) {
            @Override
            public void recordFailure(String animateurId, MailKind kind, Throwable failure) {
                journal.add("ECHEC " + animateurId + " " + kind);
            }

            @Override
            public void recordSent(String animateurId, MailKind kind) {
                journal.add("ENVOYE " + animateurId + " " + kind);
            }

            @Override
            public boolean isAddressBlocked(String animateurId) {
                return "B1".equals(animateurId);
            }
        };
        expediteur = new NotificationDispatcher(
                mails -> envoyes.addAll(List.of(mails)),
                redacteur,
                MailTemplates.standalone(ProductName.neutral()),
                new MailMetrics(registry),
                blocking);

        assertThatCode(() -> expediteur.surNotification(
                        new Notification.TargetSolicited("B1", "bob@example.org", "Alice Dupont", 1)))
                .doesNotThrowAnyException();
        expediteur.surNotification(
                new Notification.DemandeDeclinee("A1", "alice@example.org", "Bob Martin", "samedi 10h-12h"));

        assertThat(envoyes)
                .singleElement()
                .satisfies(mail -> assertThat(mail.getTo()).containsExactly("alice@example.org"));
        assertThat(journal).containsExactly("ENVOYE A1 ECHANGE_DECLINEE");
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
                new MailMetrics(registry),
                deliveries);

        assertThatCode(() -> expediteur.surNotification(oneSubmission())).doesNotThrowAnyException();
        assertThat(envoyes).isEmpty();
    }
}
