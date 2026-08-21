package dev.sylvain.planning.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.mailer.Mail;

/**
 * Les mails qu'un administrateur <b>demande</b>, sur un {@code MailService}
 * construit à la main avec un {@code Mailer} de capture : ni SMTP, ni contexte
 * Quarkus.
 *
 * <p>L'invariant propre à cette classe est l'inverse de celui des
 * notifications : ici un échec <b>doit remonter</b>. Le mail n'accompagne pas
 * une opération, il <i>est</i> l'opération — sans lui l'animateur n'a pas son
 * code et ne peut pas entrer, ou l'administrateur croit avoir diffusé un
 * planning qui n'est jamais parti. La politique best-effort est testée à part,
 * dans {@code ExpediteurNotificationsTest}.</p>
 */
class MailServiceTest {

    private final List<Mail> envoyes = new ArrayList<>();
    private MailService service;

    @BeforeEach
    void construireService() {
        service = new MailService();
        service.mailer = mails -> envoyes.addAll(List.of(mails));
        service.adresseAdmin = new AdresseAdministrateur(Optional.of("admin@example.org"));
    }

    @Test
    void lEnvoiDuPlanningJointLePdfEtLeLienEspace() {
        byte[] pdf = new byte[] { 1, 2, 3 };

        service.envoyerPlanningIndividuel("alice@example.org", "Alice",
                "https://planning.example.org/animateur/jeton-1", pdf, "planning-Alice-Martin.pdf");

        assertThat(envoyes).hasSize(1);
        Mail mail = envoyes.get(0);
        assertThat(mail.getTo()).containsExactly("alice@example.org");
        assertThat(mail.getSubject()).contains("votre planning individuel");
        assertThat(mail.getText())
                .contains("Bonjour Alice,")
                .contains("pièce jointe")
                .contains("https://planning.example.org/animateur/jeton-1");
        assertThat(mail.getAttachments()).hasSize(1);
        assertThat(mail.getAttachments().get(0).getName()).isEqualTo("planning-Alice-Martin.pdf");
        assertThat(mail.getAttachments().get(0).getContentType()).isEqualTo("application/pdf");
    }

    /** Sans URL publique (pas de lien d'espace), le mail part sans le lien. */
    @Test
    void lEnvoiDuPlanningSansLienEspaceResteComplet() {
        service.envoyerPlanningIndividuel("alice@example.org", null, null,
                new byte[] { 1 }, "planning.pdf");

        assertThat(envoyes).hasSize(1);
        assertThat(envoyes.get(0).getText())
                .contains("Bonjour,")
                .doesNotContain("espace en ligne");
    }

    /** Le code d'accès part en clair dans le corps, avec sa durée de validité. */
    @Test
    void leCodeDAccesEstEnvoyeAvecSaDureeDeValidite() {
        service.envoyerCodeAcces("alice@example.org", "Alice", "042137");

        assertThat(envoyes).hasSize(1);
        assertThat(envoyes.get(0).getSubject()).contains("code d'accès");
        assertThat(envoyes.get(0).getText())
                .contains("Bonjour Alice")
                .contains("042137")
                .contains("10 minutes");
    }

    /** Sans le mail, pas d'accès : un échec d'envoi du code doit remonter. */
    @Test
    void unEchecDEnvoiDeCodeRemonteALAppelant() {
        service.mailer = mails -> {
            throw new IllegalStateException("SMTP down");
        };

        assertThatThrownBy(() -> service.envoyerCodeAcces("alice@example.org", "Alice", "042137"))
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * Contrairement aux notifications, l'envoi du planning est une action
     * admin explicite : un échec doit remonter pour être montré, pas être
     * avalé.
     */
    @Test
    void unEchecDEnvoiDePlanningRemonteALAppelant() {
        service.mailer = mails -> {
            throw new IllegalStateException("SMTP down");
        };

        assertThatThrownBy(() -> service
                .envoyerPlanningIndividuel("alice@example.org", "Alice", null, new byte[] { 1 }, "planning.pdf"))
                .isInstanceOf(IllegalStateException.class);
    }

    /** Le mail de test de l'écran Débogage existe pour révéler un SMTP cassé. */
    @Test
    void leMailDeTestPropageSonEchec() {
        service.mailer = mails -> {
            throw new IllegalStateException("SMTP down");
        };

        assertThatThrownBy(() -> service.envoyerMailTest()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void sansAdresseAdminLeMailDeTestLeDitAuLieuDePartir() {
        service.adresseAdmin = new AdresseAdministrateur(Optional.empty());

        assertThatThrownBy(() -> service.envoyerMailTest())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MAIL_ADMIN");
        assertThat(envoyes).isEmpty();
    }
}
