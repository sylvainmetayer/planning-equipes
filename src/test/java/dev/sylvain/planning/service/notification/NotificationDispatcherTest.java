package dev.sylvain.planning.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.sylvain.planning.domain.DemandeEchange;
import dev.sylvain.planning.service.publication.AdminAddress;
import dev.sylvain.planning.service.ApplicationLinks;
import dev.sylvain.planning.service.ProductName;
import dev.sylvain.planning.service.mail.MailTemplates;
import io.quarkus.mailer.Mail;

/**
 * The delivery policy of the notifications, now written in a single place. It
 * used to be written by hand on every call — a
 * {@code try/catch (RuntimeException)} around every send, in five services —
 * and nothing stopped anyone from forgetting one.
 */
class NotificationDispatcherTest {

    private final List<Mail> envoyes = new ArrayList<>();
    private NotificationDispatcher expediteur;

    @BeforeEach
    void construireExpediteur() {
        NotificationWriter redacteur = new NotificationWriter();
        redacteur.adminAddress = new AdminAddress(Optional.of("admin@example.org"));
        redacteur.liens = new ApplicationLinks(Optional.of("https://planning.example.org"));
        redacteur.productName = ProductName.neutral();
        redacteur.templates = MailTemplates.standalone(ProductName.neutral());

        expediteur = new NotificationDispatcher();
        expediteur.redacteur = redacteur;
        expediteur.templates = MailTemplates.standalone(ProductName.neutral());
        expediteur.mailer = mails -> envoyes.addAll(List.of(mails));
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
    void unEchecDEnvoiEstAvaleSansCasserLOperation() {
        expediteur.mailer = mails -> {
            throw new IllegalStateException("SMTP down");
        };

        assertThatCode(() -> expediteur.surNotification(oneSubmission())).doesNotThrowAnyException();
    }

    /** The {@code catch} covers the writing as much as the sending. */
    @Test
    void unEchecDeRedactionEstAvaleAussi() {
        expediteur.redacteur = new NotificationWriter() {
            @Override
            public Optional<MailDraft> rediger(Notification notification) {
                throw new IllegalStateException("rédaction impossible");
            }
        };

        assertThatCode(() -> expediteur.surNotification(oneSubmission())).doesNotThrowAnyException();
        assertThat(envoyes).isEmpty();
    }
}
