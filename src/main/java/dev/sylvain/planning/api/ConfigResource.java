package dev.sylvain.planning.api;

import java.util.Optional;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Runtime configuration the frontend needs before it can call anything else:
 * where to send error reports (Sentry-protocol DSN, typically a Bugsink
 * instance) and analytics events (PostHog / Cloudflare Web Analytics). All are
 * client-facing keys by design (a Sentry DSN and analytics project tokens are
 * meant to be embedded in browser code, unlike a secret), which is what lets a single
 * build serve every environment: the values live in server-side env vars
 * (see docker-compose.yml) instead of being baked into the Angular bundle at
 * build time. Blank when unset, which the frontend treats as "disabled" —
 * see docs/observabilite.md.
 */
@Path("/config")
@Produces(MediaType.APPLICATION_JSON)
public class ConfigResource {

    // Optional<String>, not String: SmallRye Config's built-in String
    // converter turns a blank value into null, which fails Quarkus' startup
    // validation of every @ConfigProperty injection point unless the type is
    // Optional (see SentryInitializer for the same reasoning).
    @ConfigProperty(name = "observability.sentry.dsn")
    Optional<String> sentryDsn;

    @ConfigProperty(name = "observability.sentry.environment", defaultValue = "local")
    String sentryEnvironment;

    @ConfigProperty(name = "observability.posthog.api-key")
    Optional<String> posthogApiKey;

    @ConfigProperty(name = "observability.posthog.host", defaultValue = "https://eu.i.posthog.com")
    String posthogHost;

    @ConfigProperty(name = "observability.cloudflare.web-analytics-token")
    Optional<String> cloudflareWebAnalyticsToken;

    @GET
    public ConfigView get() {
        return new ConfigView(
                sentryDsn.orElse(""),
                sentryEnvironment,
                posthogApiKey.orElse(""),
                posthogHost,
                cloudflareWebAnalyticsToken.orElse(""));
    }

    /**
     * @param sentryDsn          empty disables Sentry/Bugsink error reporting
     * @param sentryEnvironment  tag attached to every reported error/transaction
     * @param posthogApiKey      empty disables PostHog analytics
     * @param posthogHost        PostHog ingestion host (EU by default for RGPD)
     * @param cloudflareWebAnalyticsToken empty disables Cloudflare Web Analytics
     */
    public record ConfigView(
            String sentryDsn,
            String sentryEnvironment,
            String posthogApiKey,
            String posthogHost,
            String cloudflareWebAnalyticsToken) {
    }
}
