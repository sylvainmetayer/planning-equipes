package dev.sylvain.planning.service.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sylvain.planning.service.ProductName;
import dev.sylvain.planning.service.mail.MailMetrics;
import dev.sylvain.planning.service.mail.MailTemplates;
import dev.sylvain.planning.service.mail.MailTemplates.MailContent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The mails an administrator <b>asks for</b>, over a {@code MailService} built
 * by hand with a capturing {@code Mailer}: no SMTP, no Quarkus context.
 *
 * <p>The invariant of this class is the opposite of the one of the
 * notifications: here a failure <b>must propagate</b>. The mail does not
 * accompany an operation, it <i>is</i> the operation — without it the
 * administrator believes they have delivered a planning that never left. The best-effort policy is tested
 * separately, in {@code NotificationDispatcherTest}.</p>
 */
class MailServiceTest {

    private final List<Mail> envoyes = new ArrayList<>();
    private final MailTemplates templates = MailTemplates.standalone(ProductName.neutral());
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private MailService service;

    @BeforeEach
    void buildService() {
        service = service(mails -> envoyes.addAll(List.of(mails)), new AdminAddress(Optional.of("admin@example.org")));
    }

    private MailService service(Mailer mailer, AdminAddress adminAddress) {
        return new MailService(mailer, adminAddress, ProductName.neutral(), templates, new MailMetrics(registry));
    }

    /** A service whose SMTP refuses everything. */
    private MailService failingService() {
        return service(
                mails -> {
                    throw new IllegalStateException("SMTP down");
                },
                new AdminAddress(Optional.of("admin@example.org")));
    }

    /** Each send is counted under the template that wrote it, never under a recipient. */
    @Test
    void aSentMailIsCountedUnderItsTemplate() {
        service.sendRelanceConfirmation("alice@example.org", "Alice", null);

        assertThat(registry.get("planning.mail.sent")
                        .tag("template", "relance-confirmation")
                        .counter()
                        .count())
                .isEqualTo(1.0);
        assertThat(registry.find("planning.mail.failures").counters()).isEmpty();
    }

    /** An SMTP failure is counted, and still reaches the caller. */
    @Test
    void aFailedSendIsCountedAndStillPropagates() {
        MailService failing = failingService();

        assertThatThrownBy(() -> failing.sendRelanceConfirmation("alice@example.org", "Alice", null))
                .isInstanceOf(IllegalStateException.class);

        assertThat(registry.get("planning.mail.failures")
                        .tag("template", "relance-confirmation")
                        .counter()
                        .count())
                .isEqualTo(1.0);
        assertThat(registry.find("planning.mail.sent").counters()).isEmpty();
    }

    @Test
    void lEnvoiDuPlanningJointLePdfEtLeLienEspace() {
        byte[] pdf = new byte[] {1, 2, 3};

        service.sendIndividualPlanning(
                "alice@example.org",
                "Alice",
                "https://planning.example.org/animateur/jeton-1",
                pdf,
                "planning-Alice-Martin.pdf");

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
        service.sendIndividualPlanning("alice@example.org", null, null, new byte[] {1}, "planning.pdf");

        assertThat(envoyes).hasSize(1);
        assertThat(envoyes.get(0).getText()).contains("Bonjour,").doesNotContain("espace en ligne");
    }

    /**
     * Unlike the notifications, sending the planning is an explicit admin
     * action: a failure must propagate to be shown, not be swallowed.
     */
    @Test
    void aFailedPlanningSendPropagatesToTheCaller() {
        service = failingService();

        assertThatThrownBy(() -> service.sendIndividualPlanning(
                        "alice@example.org", "Alice", null, new byte[] {1}, "planning.pdf"))
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * The manual reminder is an explicit click, not a nightly best effort: a
     * failure must reach the organiser, who is then told who was missed.
     */
    @Test
    void aFailedManualReminderPropagates() {
        service = failingService();

        assertThatThrownBy(() -> service.sendRelanceConfirmation("alice@example.org", "Alice", null))
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * The hand writes exactly what the night writes: the mail is held to the
     * shared rendering of {@link RelanceConfirmationMail}, as the night's
     * draft is in {@code RelanceConfirmationMailTest}.
     */
    @Test
    void theManualReminderIsTheSharedRenderingWithTheEspaceLink() {
        String lien = "https://planning.example.org/animateur/jeton-1";
        service.sendRelanceConfirmation("alice@example.org", "Alice", lien);
        MailContent partage = RelanceConfirmationMail.render(templates, ProductName.neutral(), "Alice", lien);

        assertThat(envoyes).hasSize(1);
        Mail mail = envoyes.get(0);
        assertThat(mail.getTo()).containsExactly("alice@example.org");
        assertThat(mail.getSubject()).isEqualTo(partage.subject()).contains("confirmez-vous votre planning ?");
        assertThat(mail.getText())
                .isEqualTo(partage.text())
                .contains("Bonjour Alice,")
                .contains(lien);
        assertThat(mail.getHtml()).isEqualTo(partage.html());
    }

    /** The test mail of the Débogage screen exists to reveal a broken SMTP. */
    @Test
    void theTestMailPropagatesItsFailure() {
        service = failingService();

        assertThatThrownBy(() -> service.sendTestMail()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void withoutAnAdminAddressTheTestMailSaysSoInsteadOfLeaving() {
        service = service(mails -> envoyes.addAll(List.of(mails)), new AdminAddress(Optional.empty()));

        assertThatThrownBy(() -> service.sendTestMail())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MAIL_ADMIN");
        assertThat(envoyes).isEmpty();
    }
}
