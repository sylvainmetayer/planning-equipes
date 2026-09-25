package dev.sylvain.planning.service.mail;

import dev.sylvain.planning.config.ConfigBranding;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import org.jboss.logging.Logger;

/**
 * What the HTML layout of every mail is drawn with: the deployment's logo,
 * inlined as an attachment so it shows without a public URL and without a
 * remote fetch, the palette of the PDFs — always a parseable {@code #rrggbb},
 * unlike the web accent — and the organisation named in the footer.
 *
 * @param logo            the image bytes, {@code null} when none is configured
 *                        or it could not be read
 * @param logoContentType MIME type of {@code logo}, derived from its extension
 * @param logoExtension   its file extension, for the attachment name
 */
public record MailBranding(
        byte[] logo,
        String logoContentType,
        String logoExtension,
        String headline,
        String muted,
        String accent,
        String highlight,
        String pill,
        String organisation) {

    private static final Logger LOG = Logger.getLogger(MailBranding.class);
    private static final String CLASSPATH_PREFIX = "classpath:";

    /** No logo, the default palette, no organisation: what a unit test renders with. */
    public static final MailBranding NEUTRAL =
            new MailBranding(null, null, null, "#1f2933", "#6b7280", "#3a6ea5", "#e4eaf1", "#f1f4f8", null);

    /**
     * Reads the deployment's branding. Reuses the PDF logo
     * ({@code planning.branding.pdf.logo}) rather than the web one: the latter
     * is a URL the browser resolves against the application, meaningless in a
     * mail client, while the former is bytes we hold. A logo that cannot be
     * read is logged and left out — a mail without a logo is still the mail.
     */
    public static MailBranding of(ConfigBranding config) {
        ConfigBranding.Palette palette = config.pdf().palette();
        String resource = config.pdf()
                .logo()
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .orElse(null);
        byte[] logo = null;
        String extension = null;
        if (resource != null) {
            try {
                logo = read(resource);
                extension = extension(resource);
            } catch (IOException | RuntimeException e) {
                LOG.warnf(e, "The mail logo %s could not be read; mails go out without it", resource);
            }
        }
        return new MailBranding(
                logo,
                logo == null ? null : contentType(extension),
                extension,
                palette.headline(),
                palette.muted(),
                palette.accent(),
                palette.highlight(),
                palette.pill(),
                config.organisation()
                        .map(String::trim)
                        .filter(value -> !value.isEmpty())
                        .orElse(null));
    }

    private static byte[] read(String resource) throws IOException {
        if (resource.startsWith(CLASSPATH_PREFIX)) {
            String path = resource.substring(CLASSPATH_PREFIX.length());
            try (InputStream in = MailBranding.class.getResourceAsStream(path)) {
                if (in == null) {
                    throw new IOException("Missing classpath resource: " + path);
                }
                return in.readAllBytes();
            }
        }
        return Files.readAllBytes(Path.of(resource));
    }

    private static String extension(String resource) {
        int dot = resource.lastIndexOf('.');
        return dot < 0 || dot == resource.length() - 1
                ? "png"
                : resource.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** Compares the logo by its bytes, which the generated record method would compare by identity. */
    @Override
    public boolean equals(Object other) {
        return other instanceof MailBranding that
                && Arrays.equals(logo, that.logo)
                && Objects.equals(logoContentType, that.logoContentType)
                && Objects.equals(logoExtension, that.logoExtension)
                && Objects.equals(headline, that.headline)
                && Objects.equals(muted, that.muted)
                && Objects.equals(accent, that.accent)
                && Objects.equals(highlight, that.highlight)
                && Objects.equals(pill, that.pill)
                && Objects.equals(organisation, that.organisation);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(logo)
                + Objects.hash(logoContentType, logoExtension, headline, muted, accent, highlight, pill, organisation);
    }

    /** Names the logo by its size: its bytes are no use in a log line. */
    @Override
    public String toString() {
        return "MailBranding[logo=" + (logo == null ? "null" : logo.length + " bytes")
                + ", logoContentType=" + logoContentType
                + ", logoExtension=" + logoExtension
                + ", headline=" + headline
                + ", muted=" + muted
                + ", accent=" + accent
                + ", highlight=" + highlight
                + ", pill=" + pill
                + ", organisation=" + organisation + "]";
    }

    private static String contentType(String extension) {
        return switch (extension) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "svg" -> "image/svg+xml";
            case "webp" -> "image/webp";
            default -> "image/png";
        };
    }
}
