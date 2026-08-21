package dev.sylvain.planning.observability;

import java.util.Optional;
import java.util.regex.Pattern;

import io.quarkus.runtime.StartupEvent;
import io.sentry.Sentry;
import io.sentry.SentryEvent;
import io.sentry.protocol.SentryException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;

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
 * serving {@code /api/espace-animateur/{jeton}/…} would otherwise carry it
 * into an error tracker, in clear, for as long as that tracker keeps it. The
 * frontend does the same on its side.</p>
 */
@ApplicationScoped
public class SentryInitializer {

    // Optional<String>, not String with defaultValue="": SmallRye Config's
    // built-in String converter turns a blank value into null, which fails
    // Quarkus' startup validation of every @ConfigProperty injection point
    // unless the type is Optional.
    void onStart(@Observes StartupEvent event,
            @ConfigProperty(name = "observability.sentry.dsn") Optional<String> dsn,
            @ConfigProperty(name = "observability.sentry.environment", defaultValue = "local") String environment) {
        if (dsn.isEmpty()) {
            return;
        }
        Sentry.init(options -> {
            options.setDsn(dsn.get());
            options.setEnvironment(environment);
            options.setBeforeSend((rapport, hint) -> masquerJetonsDuRapport(rapport));
        });
    }

    /**
     * Access token of an espace animateur URL, in the two shapes it takes.
     * Mirrors {@code masquerJetonEspace} on the frontend — the same promise is
     * made to the reader of the privacy policy on both sides.
     */
    private static final Pattern JETON_ESPACE =
            Pattern.compile("/(api/espace-animateur|animateur)/[^/?#\\s\"']+");

    static String masquerJeton(String valeur) {
        return valeur == null ? null : JETON_ESPACE.matcher(valeur).replaceAll("/$1/<jeton>");
    }

    /** Masks the message of the event and of every exception it carries. */
    static SentryEvent masquerJetonsDuRapport(SentryEvent event) {
        if (event.getMessage() != null) {
            event.getMessage().setFormatted(masquerJeton(event.getMessage().getFormatted()));
        }
        if (event.getExceptions() != null) {
            for (SentryException exception : event.getExceptions()) {
                exception.setValue(masquerJeton(exception.getValue()));
            }
        }
        return event;
    }
}
