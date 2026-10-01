package dev.sylvain.planning.service.notification;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.service.ProductName;
import dev.sylvain.planning.service.espace.ApplicationLinks;
import dev.sylvain.planning.service.mail.MailTemplates;
import dev.sylvain.planning.service.mail.MailTemplates.MailContent;
import dev.sylvain.planning.service.publication.AdminAddress;
import dev.sylvain.planning.service.publication.RelanceConfirmationMail;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The reminder of the silent leaves two ways — the night's best-effort
 * notification, and the organiser's « Relancer maintenant » through
 * {@code MailService} — and the person reading it must not be able to tell
 * which one wrote. Both are anchored on {@link RelanceConfirmationMail}: this
 * half holds the night's draft to that rendering word for word, and
 * {@code MailServiceTest} holds the hand's mail to the same rendering, with
 * the same {@link MailTemplates#standalone} engine and no container.
 */
class RelanceConfirmationMailTest {

    private static final String LIEN = "https://planning.example.org/animateur/jeton-1";

    private final MailTemplates templates = MailTemplates.standalone(ProductName.neutral());
    private NotificationWriter writer;

    @BeforeEach
    void buildTheNightPath() {
        writer = new NotificationWriter(
                new AdminAddress(Optional.empty()),
                new ApplicationLinks(Optional.of("https://planning.example.org")),
                ProductName.neutral(),
                templates);
    }

    @Test
    void theNightWritesExactlyTheSharedRendering() {
        MailDraft nuit = writer.rediger(new Notification.RelanceConfirmation("A1", "alice@example.org", "Alice", LIEN))
                .orElseThrow();
        MailContent partage = RelanceConfirmationMail.render(templates, ProductName.neutral(), "Alice", LIEN);

        assertThat(nuit.destinataire()).isEqualTo("alice@example.org");
        assertThat(nuit.sujet()).isEqualTo(partage.subject());
        assertThat(nuit.corps()).isEqualTo(partage.text());
        assertThat(nuit.html()).isEqualTo(partage.html());
    }

    /** The two edge cases of the template — no first name, no link — stay aligned too. */
    @Test
    void theNightAgreesWithoutAFirstNameAndWithoutALink() {
        MailDraft nuit = writer.rediger(new Notification.RelanceConfirmation("A1", "alice@example.org", "  ", null))
                .orElseThrow();
        MailContent partage = RelanceConfirmationMail.render(templates, ProductName.neutral(), "  ", null);

        // No first name means no greeting line at all, and no link means no URL.
        assertThat(nuit.corps())
                .isEqualTo(partage.text())
                .doesNotContain("Bonjour")
                .doesNotContain("http");
        assertThat(nuit.html()).isEqualTo(partage.html());
    }

    @Test
    void theSharedRenderingSaysWhatIsExpectedAndWhere() {
        MailContent partage = RelanceConfirmationMail.render(templates, ProductName.neutral(), "Alice", LIEN);

        assertThat(partage.subject()).contains("confirmez-vous votre planning ?");
        assertThat(partage.text())
                .contains("Bonjour Alice,")
                .contains("J'ai lu et je serai là")
                .contains(LIEN);
    }
}
