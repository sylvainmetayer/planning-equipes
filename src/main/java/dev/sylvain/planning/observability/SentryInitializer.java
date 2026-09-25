package dev.sylvain.planning.observability;

import dev.sylvain.planning.config.ConfigObservabilite;
import io.quarkus.runtime.StartupEvent;
import io.sentry.Sentry;
import io.sentry.SentryEvent;
import io.sentry.protocol.SentryException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Initializes the Sentry Java SDK at startup. No Quarkus extension exists for
 * Sentry, so the plain SDK is wired manually here instead. Left disabled
 * (every {@code Sentry.*} call becomes a no-op) unless {@code SENTRY_DSN} is
 * set, so local/dev/test runs never send anything anywhere by default. The
 * DSN is expected to point at a Bugsink instance (hosted or self-hosted, Sentry-SDK
 * compatible error tracker) rather than Sentry SaaS, but any Sentry-protocol
 * endpoint works — see docs/observabilite.md.
 *
 * <p>Whatever it reports has the espace animateur's access token stripped from
 * it first. That token is a unique, stable identifier of one named person,
 * often a minor, and it travels in the URL path — so an exception raised while
 * serving {@code /api/espace-animateur/{token}/…} would otherwise carry it
 * into an error tracker, in clear, for as long as that tracker keeps it. The
 * wall display's token ({@code /api/mural/{token}}) is scrubbed the same way:
 * it opens the whole day's staffing, names included, to whoever holds it. The
 * frontend does the same on its side.</p>
 */
@ApplicationScoped
public class SentryInitializer {

    void onStart(@Observes StartupEvent event, ConfigObservabilite observabilite) {
        Optional<String> dsn = observabilite.sentry().dsn();
        String environment = observabilite.sentry().environment();
        if (dsn.isEmpty()) {
            return;
        }
        Sentry.init(options -> {
            options.setDsn(dsn.get());
            options.setEnvironment(environment);
            options.setBeforeSend((rapport, hint) -> maskTokensInReport(rapport));
        });
    }

    /**
     * Token of an espace animateur or wall-display URL, in the two shapes each
     * takes (the page and the API call). Mirrors {@code maskUrlToken} on the
     * frontend — the same promise is made to the reader of the privacy policy
     * on both sides. {@code /api/affichage-mural/{id}} carries an id, not a
     * token, and does not match: a hyphen, not a slash, precedes {@code mural}.
     */
    private static final Pattern URL_TOKEN =
            Pattern.compile("/(api/espace-animateur|animateur|api/mural|mural)/[^/?#\\s\"']+");

    static String maskToken(String value) {
        return value == null ? null : URL_TOKEN.matcher(value).replaceAll("/$1/<jeton>");
    }

    /** Masks the message of the event and of every exception it carries. */
    static SentryEvent maskTokensInReport(SentryEvent event) {
        if (event.getMessage() != null) {
            event.getMessage().setFormatted(maskToken(event.getMessage().getFormatted()));
        }
        if (event.getExceptions() != null) {
            for (SentryException exception : event.getExceptions()) {
                exception.setValue(maskToken(exception.getValue()));
            }
        }
        return event;
    }
}
