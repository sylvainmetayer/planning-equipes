package dev.sylvain.planning.api;

import dev.sylvain.planning.config.ConfigAdmin;
import dev.sylvain.planning.config.ConfigObservabilite;
import dev.sylvain.planning.config.DevMode;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;
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
 *
 * <p>And whether the admin's day views offer <b>drag and drop</b> to move a
 * seat by hand — see {@link ConfigAdmin#dragDropEnabled()}.</p>
 *
 * <p>And which <b>version</b> of the backend answers — the value the startup
 * line prints, derived from the git tag the image was built from
 * (docs/versioning.md). It is what {@code scripts/verifier-deploiement.sh}
 * compares with the version the operator meant to deploy; the footer of every
 * page already shows it, so publishing it here reveals nothing new.</p>
 */
@Path("/config")
@Produces(MediaType.APPLICATION_JSON)
public class ConfigResource {

    private final ConfigObservabilite observabilite;

    private final DevMode devMode;

    private final ConfigAdmin admin;

    private final String version;

    @Inject
    public ConfigResource(
            ConfigObservabilite observabilite,
            DevMode devMode,
            ConfigAdmin admin,
            @ConfigProperty(name = "quarkus.application.version") String version) {
        this.observabilite = observabilite;
        this.devMode = devMode;
        this.admin = admin;
        this.version = version;
    }

    @GET
    public ConfigView get() {
        return new ConfigView(
                observabilite.sentry().dsn().orElse(""),
                observabilite.sentry().environment(),
                observabilite.cloudflare().webAnalyticsToken().orElse(""),
                devMode.isActive(),
                admin.dragDropEnabled(),
                version);
    }

    /**
     * @param sentryDsn          empty disables Sentry/Bugsink error reporting
     * @param sentryEnvironment  tag attached to every reported error/transaction
     * @param cloudflareWebAnalyticsToken empty disables Cloudflare Web Analytics
     * @param devMode            server launched with {@code quarkus:dev}: the
     *                           Dev UI exists at {@code /q/dev-ui}, so the
     *                           interface may link to it
     * @param dragDropEnabled    the admin's day views let a seat be dragged
     *                           onto another line
     * @param version            backend version: {@code X.Y.Z} on a release
     *                           image, the short SHA on a recette image,
     *                           {@code 999-SNAPSHOT} in a local build
     */
    @Schema(requiredProperties = {"devMode", "dragDropEnabled", "version"})
    public record ConfigView(
            String sentryDsn,
            String sentryEnvironment,
            String cloudflareWebAnalyticsToken,
            boolean devMode,
            boolean dragDropEnabled,
            String version) {}
}
