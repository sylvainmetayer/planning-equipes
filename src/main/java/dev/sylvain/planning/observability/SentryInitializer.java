package dev.sylvain.planning.observability;

import java.util.Optional;

import io.quarkus.runtime.StartupEvent;
import io.sentry.Sentry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Initializes the Sentry Java SDK at startup. No Quarkus extension exists for
 * Sentry, so the plain SDK is wired manually here instead. Left disabled
 * (every {@code Sentry.*} call becomes a no-op) unless {@code SENTRY_DSN} is
 * set, so local/dev/test runs never send anything anywhere by default. The
 * DSN is expected to point at a Bugsink instance (self-hosted, Sentry-SDK
 * compatible error tracker) rather than Sentry SaaS, but any Sentry-protocol
 * endpoint works — see docs/observabilite.md.
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
        });
    }
}
