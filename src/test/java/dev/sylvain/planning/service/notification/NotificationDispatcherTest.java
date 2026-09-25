package dev.sylvain.planning.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import dev.sylvain.planning.domain.DemandeEchange;
import dev.sylvain.planning.service.ProductName;
import dev.sylvain.planning.service.espace.ApplicationLinks;
import dev.sylvain.planning.service.mail.MailTemplates;
import dev.sylvain.planning.service.publication.AdminAddress;
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

    @BeforeEach
    void buildDispatcher() {
        expediteur = new NotificationDispatcher(
                mails -> envoyes.addAll(List.of(mails)), redacteur, MailTemplates.standalone(ProductName.neutral()));
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
                MailTemplates.standalone(ProductName.neutral()));

        assertThatCode(() -> expediteur.surNotification(oneSubmission())).doesNotThrowAnyException();
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
                mails -> envoyes.addAll(List.of(mails)), failing, MailTemplates.standalone(ProductName.neutral()));

        assertThatCode(() -> expediteur.surNotification(oneSubmission())).doesNotThrowAnyException();
        assertThat(envoyes).isEmpty();
    }
}
