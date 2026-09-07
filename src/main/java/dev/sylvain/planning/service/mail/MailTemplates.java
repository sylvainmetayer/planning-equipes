package dev.sylvain.planning.service.mail;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import dev.sylvain.planning.config.ConfigBranding;
import dev.sylvain.planning.service.ProductName;
import io.quarkus.mailer.Mail;
import io.quarkus.qute.Engine;
import io.quarkus.qute.HtmlEscaper;
import io.quarkus.qute.ReflectionValueResolver;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.qute.TemplateLocator;
import io.quarkus.qute.Variant;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Where every mail body comes from: a pair of Qute templates under
 * {@code resources/templates/mail/}, {@code <name>.txt} for the plain-text
 * part and {@code <name>.html} for the HTML alternative, the latter built on
 * the shared {@code mail/layout.html} — the deployment's logo, palette and
 * name around the message. The wording lives in the templates and nowhere
 * else; the Java side names the template and hands it its values.
 *
 * <p>Two engines, one contract. Under Quarkus the injected {@link Engine} is
 * the one the extension validated at build time; {@link #standalone} builds
 * the same thing from the classpath for the unit tests, which assert on the
 * wording without a container. Both remove standalone lines and escape HTML
 * in {@code .html} templates, so a name typed with a {@code <} renders as
 * text, never as markup.</p>
 *
 * <p>The HTML part is an alternative, never a replacement: {@link #toMail}
 * always sends the text part as well, which is what the tests read and what a
 * client refusing HTML shows.</p>
 */
@ApplicationScoped
public class MailTemplates {

    /** Content id of the inlined logo, referenced as {@code cid:} by the layout. */
    static final String LOGO_CID = "logo@planning-equipes";

    @Inject
    Engine engine;

    @Inject
    ProductName productName;

    @Inject
    ConfigBranding config;

    private MailBranding branding;

    MailTemplates() {
    }

    private MailTemplates(Engine engine, ProductName productName, MailBranding branding) {
        this.engine = engine;
        this.productName = productName;
        this.branding = branding;
    }

    @PostConstruct
    void init() {
        branding = MailBranding.of(config);
    }

    /** The engine of a unit test: the same templates, read from the classpath, no branding image. */
    public static MailTemplates standalone(ProductName productName) {
        return standalone(productName, MailBranding.NEUTRAL);
    }

    public static MailTemplates standalone(ProductName productName, MailBranding branding) {
        return new MailTemplates(standaloneEngine(), productName, branding);
    }

    /**
     * A subject, and the text and HTML bodies of one mail.
     */
    public record MailContent(String subject, String text, String html) {
    }

    /**
     * Renders both parts of {@code template} ({@code mail/xxx}, without a
     * suffix) with {@code values} — keys the templates read, {@code null}
     * allowed and read as absent by {@code {#if}}.
     */
    public MailContent render(String template, String subject, Map<String, Object> values) {
        Map<String, Object> data = new HashMap<>(values);
        data.put("sujet", subject);
        data.put("productName", productName.value());
        data.put("organisation", branding.organisation());
        data.put("headline", branding.headline());
        data.put("muted", branding.muted());
        data.put("accent", branding.accent());
        data.put("highlight", branding.highlight());
        data.put("pill", branding.pill());
        data.put("logoCid", branding.logo() == null ? null : LOGO_CID);
        return new MailContent(subject, render(template + ".txt", data), render(template + ".html", data));
    }

    private String render(String id, Map<String, Object> data) {
        TemplateInstance instance = engine.getTemplate(id).instance();
        data.forEach(instance::data);
        return instance.render();
    }

    /** The values of a template, in {@code key, value} pairs; {@code null} values are kept. */
    public static Map<String, Object> values(Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("Template values come in key/value pairs");
        }
        Map<String, Object> values = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            values.put((String) keyValues[i], keyValues[i + 1]);
        }
        return values;
    }

    public Mail toMail(String to, MailContent content) {
        return toMail(to, content.subject(), content.text(), content.html());
    }

    /** A mail carrying both parts, the logo inlined when the deployment has one. */
    public Mail toMail(String to, String subject, String text, String html) {
        Mail mail = Mail.withText(to, subject, text);
        if (html != null && !html.isBlank()) {
            mail.setHtml(html);
            if (branding.logo() != null) {
                mail.addInlineAttachment("logo." + branding.logoExtension(), branding.logo(),
                        branding.logoContentType(), "<" + LOGO_CID + ">");
            }
        }
        return mail;
    }

    private static Engine standaloneEngine() {
        return Engine.builder()
                .addDefaults()
                .addValueResolver(new ReflectionValueResolver())
                .removeStandaloneLines(true)
                .addResultMapper(new HtmlEscaper(List.of(Variant.TEXT_HTML)))
                .addLocator(MailTemplates::locate)
                .build();
    }

    /** Resolves {@code mail/xxx.txt} directly, and a suffix-less {@code mail/layout} the way Quarkus does. */
    private static Optional<TemplateLocator.TemplateLocation> locate(String id) {
        List<String> candidates = id.contains(".") ? List.of(id) : List.of(id + ".html", id + ".txt");
        for (String candidate : candidates) {
            URL url = MailTemplates.class.getClassLoader().getResource("templates/" + candidate);
            if (url != null) {
                String contentType = candidate.endsWith(".html") ? Variant.TEXT_HTML : Variant.TEXT_PLAIN;
                return Optional.of(new TemplateLocator.TemplateLocation() {
                    @Override
                    public Reader read() {
                        try {
                            return new InputStreamReader(url.openStream(), StandardCharsets.UTF_8);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    }

                    @Override
                    public Optional<Variant> getVariant() {
                        return Optional.of(Variant.forContentType(contentType));
                    }
                });
            }
        }
        return Optional.empty();
    }
}
