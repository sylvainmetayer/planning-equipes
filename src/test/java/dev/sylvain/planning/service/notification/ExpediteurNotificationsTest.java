package dev.sylvain.planning.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.sylvain.planning.domain.DemandeEchange;
import dev.sylvain.planning.service.AdresseAdministrateur;
import dev.sylvain.planning.service.LiensApplication;
import io.quarkus.mailer.Mail;

/**
 * La politique de livraison des notifications, désormais écrite à un seul
 * endroit. Elle l'était auparavant à la main sur chaque appel — un
 * {@code try/catch (RuntimeException)} autour de chaque envoi, dans cinq
 * services — et rien n'empêchait d'en oublier un.
 */
class ExpediteurNotificationsTest {

    private final List<Mail> envoyes = new ArrayList<>();
    private ExpediteurNotifications expediteur;

    @BeforeEach
    void construireExpediteur() {
        RedacteurNotifications redacteur = new RedacteurNotifications();
        redacteur.adresseAdmin = new AdresseAdministrateur(Optional.of("admin@example.org"));
        redacteur.liens = new LiensApplication(Optional.of("https://planning.example.org"));

        expediteur = new ExpediteurNotifications();
        expediteur.redacteur = redacteur;
        expediteur.mailer = mails -> envoyes.addAll(List.of(mails));
    }

    private static Notification uneSoumission() {
        DemandeEchange demande = new DemandeEchange();
        demande.setId("D1");
        demande.setPrevalidationOk(true);
        return new Notification.DemandesSoumises("Alice Dupont", List.of(demande));
    }

    @Test
    void uneNotificationRedigeeEstEnvoyeeAuDestinataireEcrit() {
        expediteur.surNotification(uneSoumission());

        assertThat(envoyes).singleElement().satisfies(mail -> {
            assertThat(mail.getTo()).containsExactly("admin@example.org");
            assertThat(mail.getSubject()).contains("nouvelle demande d'échange");
        });
    }

    /** Personne à prévenir : rien ne part, et surtout rien n'échoue. */
    @Test
    void uneNotificationSansDestinataireNEnvoieRien() {
        expediteur.surNotification(new Notification.DemandesSoumises("Alice Dupont", List.of()));

        assertThat(envoyes).isEmpty();
    }

    /**
     * L'invariant central : un SMTP en panne ne doit jamais faire échouer
     * l'opération métier que la notification décrit — elle a déjà eu lieu.
     */
    @Test
    void unEchecDEnvoiEstAvaleSansCasserLOperation() {
        expediteur.mailer = mails -> {
            throw new IllegalStateException("SMTP down");
        };

        assertThatCode(() -> expediteur.surNotification(uneSoumission())).doesNotThrowAnyException();
    }

    /** Le {@code catch} couvre la rédaction autant que l'envoi. */
    @Test
    void unEchecDeRedactionEstAvaleAussi() {
        expediteur.redacteur = new RedacteurNotifications() {
            @Override
            public Optional<Courrier> rediger(Notification notification) {
                throw new IllegalStateException("rédaction impossible");
            }
        };

        assertThatCode(() -> expediteur.surNotification(uneSoumission())).doesNotThrowAnyException();
        assertThat(envoyes).isEmpty();
    }
}
