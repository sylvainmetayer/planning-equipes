package dev.sylvain.planning.api;

import dev.sylvain.planning.config.ConfigObservabilite;
import dev.sylvain.planning.config.DevMode;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Runtime configuration the frontend needs before it can call anything else:
 * where to send error reports (Sentry-protocol DSN, typically a Bugsink
 * instance) and analytics events (Cloudflare Web Analytics). Both are
 * client-facing keys by design (a Sentry DSN and an analytics site token are
 * meant to be embedded in browser code, unlike a secret), which is what lets a single
 * build serve every environment: the values live in server-side env vars
 * (see docker-compose.yml) instead of being baked into the Angular bundle at
 * build time. Blank when unset, which the frontend treats as "disabled" —
 * see docs/observabilite.md.
 *
 * <p>It also answers whether the server runs in <b>dev mode</b>, which is what
 * lets the UI offer the Quarkus Dev UI only where it exists. The frontend's own
 * build mode would be a poor proxy: it says how the bundle was built, not how
 * the server it talks to was launched.</p>
 */
@Path("/config")
@Produces(MediaType.APPLICATION_JSON)
public class ConfigResource {

    @Inject
    ConfigObservabilite observabilite;

    @Inject
    DevMode devMode;

    @GET
    public ConfigView get() {
        return new ConfigView(
                observabilite.sentry().dsn().orElse(""),
                observabilite.sentry().environment(),
                observabilite.cloudflare().webAnalyticsToken().orElse(""),
                devMode.isActive());
    }

    /**
     * @param sentryDsn          empty disables Sentry/Bugsink error reporting
     * @param sentryEnvironment  tag attached to every reported error/transaction
     * @param cloudflareWebAnalyticsToken empty disables Cloudflare Web Analytics
     * @param devMode            server launched with {@code quarkus:dev}: the
     *                           Dev UI exists at {@code /q/dev-ui}, so the
     *                           interface may link to it
     */
    @Schema(requiredProperties = {"devMode"})
    public record ConfigView(
            String sentryDsn, String sentryEnvironment, String cloudflareWebAnalyticsToken, boolean devMode) {}
}
