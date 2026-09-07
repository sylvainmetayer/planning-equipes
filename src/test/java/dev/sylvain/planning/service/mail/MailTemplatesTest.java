package dev.sylvain.planning.service.mail;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import dev.sylvain.planning.service.ProductName;
import dev.sylvain.planning.service.mail.MailTemplates.MailContent;
import io.quarkus.mailer.Mail;
import org.junit.jupiter.api.Test;

/**
 * The HTML side of every mail, which the wording tests do not read: the shared
 * layout around the message, the branding it carries, and the escaping that
 * keeps a name from becoming markup. No container: the standalone engine reads
 * the very same templates from the classpath.
 */
class MailTemplatesTest {

    private static final MailBranding BRANDED = new MailBranding(new byte[] { 1, 2, 3 }, "image/png", "png",
            "#111111", "#666666", "#aa0000", "#eeeeee", "#f5f5f5", "Les Bénévoles du Jeu");

    @Test
    void bothPartsSayTheSameThingAndTheHtmlOneWearsTheLayout() {
        MailTemplates templates = MailTemplates.standalone(new ProductName("Planning Équipes"), BRANDED);

        MailContent content = templates.render("mail/code-acces", "Planning Équipes — votre code d'accès",
                MailTemplates.values("prenom", "Alice", "code", "123456"));

        assertThat(content.text())
                .startsWith("Bonjour Alice,\n\n")
                .contains("Voici votre code d'accès à votre espace animateur : 123456")
                .doesNotContain("<");
        assertThat(content.html())
                // The subject is a value, so the layout escapes it like any other.
                .contains("<title>Planning Équipes — votre code d&#39;accès</title>")
                .contains("Bonjour Alice,")
                .contains("123456")
                .contains("cid:" + MailTemplates.LOGO_CID)
                .contains("border-top:4px solid #aa0000")
                .contains("Les Bénévoles du Jeu — Planning Équipes");
    }

    @Test
    void aNameTypedWithMarkupStaysTextInTheHtmlPart() {
        MailTemplates templates = MailTemplates.standalone(ProductName.neutral());

        MailContent content = templates.render("mail/echange-propose", "sujet",
                MailTemplates.values("demandeur", "<script>alert(1)</script> Dupont", "nombre", 2));

        assertThat(content.html()).doesNotContain("<script>").contains("&lt;script&gt;");
        assertThat(content.text()).contains("<script>alert(1)</script> Dupont vous propose 2 échanges de créneaux.");
    }

    @Test
    void theLogoTravelsInlineOnlyWhenTheDeploymentHasOne() {
        MailContent content = new MailContent("sujet", "texte", "<p>html</p>");

        Mail branded = MailTemplates.standalone(ProductName.neutral(), BRANDED).toMail("a@example.org", content);
        assertThat(branded.getText()).isEqualTo("texte");
        assertThat(branded.getHtml()).isEqualTo("<p>html</p>");
        assertThat(branded.getAttachments()).hasSize(1);
        assertThat(branded.getAttachments().get(0).isInlineAttachment()).isTrue();
        assertThat(branded.getAttachments().get(0).getContentId()).isEqualTo("<" + MailTemplates.LOGO_CID + ">");
        assertThat(branded.getAttachments().get(0).getContentType()).isEqualTo("image/png");

        Mail neutral = MailTemplates.standalone(ProductName.neutral()).toMail("a@example.org", content);
        assertThat(neutral.getHtml()).isEqualTo("<p>html</p>");
        assertThat(neutral.getAttachments()).isEmpty();
        assertThat(MailTemplates.standalone(ProductName.neutral()).render("mail/test", "s",
                MailTemplates.values("horodatage", "x")).html()).doesNotContain("cid:");
    }

    /**
     * The hole moving the wording out of Java opens: the exhaustive
     * {@code switch} of {@code NotificationWriter} still forces a case per
     * notification, but a case naming a template nobody wrote compiles, and
     * only fails the day that mail goes out. So every {@code mail/x} the two
     * mail classes name must exist as both parts, and every part must have its
     * twin — a text-only mail would silently lose its HTML, and the reverse
     * would send nothing readable to a client refusing HTML.
     */
    @Test
    void everyTemplateNamedInTheCodeExistsInBothParts() throws IOException {
        List<Path> sources = List.of(
                Path.of("src/main/java/dev/sylvain/planning/service/MailService.java"),
                Path.of("src/main/java/dev/sylvain/planning/service/notification/NotificationWriter.java"));
        Set<String> names = new TreeSet<>();
        for (Path source : sources) {
            Matcher matcher = Pattern.compile("\"mail/([a-z-]+)\"").matcher(Files.readString(source));
            while (matcher.find()) {
                names.add(matcher.group(1));
            }
        }
        assertThat(names).as("templates named by the mail classes").hasSizeGreaterThanOrEqualTo(12);

        Path folder = Path.of("src/main/resources/templates/mail");
        for (String name : names) {
            assertThat(folder.resolve(name + ".txt")).as("text part of %s", name).exists();
            assertThat(folder.resolve(name + ".html")).as("HTML part of %s", name).exists();
        }
        try (Stream<Path> files = Files.list(folder)) {
            for (Path file : files.toList()) {
                String fileName = file.getFileName().toString();
                if (fileName.equals("layout.html")) {
                    continue;
                }
                String twin = fileName.endsWith(".txt")
                        ? fileName.replace(".txt", ".html")
                        : fileName.replace(".html", ".txt");
                assertThat(folder.resolve(twin)).as("twin of %s", fileName).exists();
            }
        }
    }

    @Test
    void listsAndConditionsRenderTheSameInBothParts() {
        MailTemplates templates = MailTemplates.standalone(ProductName.neutral());

        MailContent content = templates.render("mail/planning-publie", "sujet", MailTemplates.values(
                "prenom", null,
                "lienEspace", null,
                "premiereDiffusion", false,
                "changements", List.of("samedi : A remplace B", "dimanche : libre"),
                "demandes", List.of()));

        assertThat(content.text())
                .startsWith("Bonjour,\n\nVotre planning a changé depuis le dernier envoi. Voici ce qui vous concerne :\n\n"
                        + "- samedi : A remplace B\n- dimanche : libre\n\nLe planning à jour est en pièce jointe.\n")
                .doesNotContain("Vos demandes d'échange")
                .doesNotContain("Votre espace en ligne")
                .endsWith("\nÀ bientôt,\nL'équipe d'organisation\n");
        assertThat(content.html())
                .contains("<li style=\"margin:0 0 4px;\">samedi : A remplace B</li>")
                .doesNotContain("Vos demandes d'échange")
                .doesNotContain("href");
    }
}
