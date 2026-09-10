package dev.sylvain.planning.service.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.sylvain.planning.service.mail.MailTemplates;
import io.quarkus.mailer.Mail;
import dev.sylvain.planning.service.ProductName;

/**
 * The mails an administrator <b>asks for</b>, over a {@code MailService} built
 * by hand with a capturing {@code Mailer}: no SMTP, no Quarkus context.
 *
 * <p>The invariant of this class is the opposite of the one of the
 * notifications: here a failure <b>must propagate</b>. The mail does not
 * accompany an operation, it <i>is</i> the operation — without it the animateur
 * has no code and cannot get in, or the administrator believes they have
 * delivered a planning that never left. The best-effort policy is tested
 * separately, in {@code NotificationDispatcherTest}.</p>
 */
class MailServiceTest {

    private final List<Mail> envoyes = new ArrayList<>();
    private MailService service;

    @BeforeEach
    void construireService() {
        service = new MailService();
        service.mailer = mails -> envoyes.addAll(List.of(mails));
        service.adminAddress = new AdminAddress(Optional.of("admin@example.org"));
        service.productName = ProductName.neutral();
        service.templates = MailTemplates.standalone(ProductName.neutral());
    }

    @Test
    void lEnvoiDuPlanningJointLePdfEtLeLienEspace() {
        byte[] pdf = new byte[] { 1, 2, 3 };

        service.sendIndividualPlanning("alice@example.org", "Alice",
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

    /** With no public URL (no espace link), the mail leaves without the link. */
    @Test
    void lEnvoiDuPlanningSansLienEspaceResteComplet() {
        service.sendIndividualPlanning("alice@example.org", null, null,
                new byte[] { 1 }, "planning.pdf");

        assertThat(envoyes).hasSize(1);
        assertThat(envoyes.get(0).getText())
                .contains("Bonjour,")
                .doesNotContain("espace en ligne");
    }

    /** The access code leaves in clear in the body, with how long it is valid. */
    @Test
    void leCodeDAccesEstEnvoyeAvecSaDureeDeValidite() {
        service.sendAccessCode("alice@example.org", "Alice", "042137");

        assertThat(envoyes).hasSize(1);
        assertThat(envoyes.get(0).getSubject()).contains("code d'accès");
        assertThat(envoyes.get(0).getText())
                .contains("Bonjour Alice")
                .contains("042137")
                .contains("10 minutes");
    }

    /** No mail, no access: a failure to send the code must propagate. */
    @Test
    void unEchecDEnvoiDeCodeRemonteALAppelant() {
        service.mailer = mails -> {
            throw new IllegalStateException("SMTP down");
        };

        assertThatThrownBy(() -> service.sendAccessCode("alice@example.org", "Alice", "042137"))
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * Unlike the notifications, sending the planning is an explicit admin
     * action: a failure must propagate to be shown, not be swallowed.
     */
    @Test
    void unEchecDEnvoiDePlanningRemonteALAppelant() {
        service.mailer = mails -> {
            throw new IllegalStateException("SMTP down");
        };

        assertThatThrownBy(() -> service
                .sendIndividualPlanning("alice@example.org", "Alice", null, new byte[] { 1 }, "planning.pdf"))
                .isInstanceOf(IllegalStateException.class);
    }

    /** The test mail of the Débogage screen exists to reveal a broken SMTP. */
    @Test
    void leMailDeTestPropageSonEchec() {
        service.mailer = mails -> {
            throw new IllegalStateException("SMTP down");
        };

        assertThatThrownBy(() -> service.sendTestMail()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void sansAdresseAdminLeMailDeTestLeDitAuLieuDePartir() {
        service.adminAddress = new AdminAddress(Optional.empty());

        assertThatThrownBy(() -> service.sendTestMail())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MAIL_ADMIN");
        assertThat(envoyes).isEmpty();
    }
}
