package dev.sylvain.planning.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.sylvain.planning.config.ConfigObservabilite;
import dev.sylvain.planning.config.ConfigOidc;
import dev.sylvain.planning.config.ConfigSecours;
import dev.sylvain.planning.config.DevMode;
import io.quarkus.security.identity.SecurityIdentity;
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
 * see site/src/docs/installer/observabilite.md.
 *
 * <p>It also answers whether the server runs in <b>dev mode</b>, which is what
 * lets the UI offer the Quarkus Dev UI only where it exists. The frontend's own
 * build mode would be a poor proxy: it says how the bundle was built, not how
 * the server it talks to was launched.</p>
 *
 * <p>And which <b>version</b> of the backend answers — the value the startup
 * line prints, derived from the git tag the image was built from
 * (site/src/docs/installer/versioning.md). It is what {@code scripts/verifier-deploiement.sh}
 * compares with the version the operator meant to deploy; the footer of every
 * page already shows it, so publishing it here reveals nothing new.</p>
 *
 * <p>And whether the mailer is <b>mocked</b> ({@code MAIL_MOCK=true}): nothing
 * leaves then, yet every send is recorded as gone, and the Animateurs page
 * must word that « simulé » rather than let it pass for a delivery.</p>
 *
 * <p>The route is public: the login page and the espace animateur read it
 * before anybody signs in. A <b>visitor without a session</b> therefore gets
 * only what those two screens need — the observability keys and the two
 * sign-in doors — and none of the three answers above, which only the admin
 * interface reads: the version would tell an attacker which known flaw to aim
 * at. The login page reads the answer again once a session is open.</p>
 */
@Path("/config")
@Produces(MediaType.APPLICATION_JSON)
public class ConfigResource {

    private final ConfigObservabilite observabilite;

    private final DevMode devMode;

    private final String version;

    private final boolean mailMock;

    private final ConfigOidc oidc;

    private final ConfigSecours secours;

    private final SecurityIdentity identity;

    @Inject
    public ConfigResource(
            ConfigObservabilite observabilite,
            DevMode devMode,
            @ConfigProperty(name = "quarkus.application.version") String version,
            @ConfigProperty(name = "quarkus.mailer.mock", defaultValue = "false") boolean mailMock,
            ConfigOidc oidc,
            ConfigSecours secours,
            SecurityIdentity identity) {
        this.observabilite = observabilite;
        this.devMode = devMode;
        this.version = version;
        this.mailMock = mailMock;
        this.oidc = oidc;
        this.secours = secours;
        this.identity = identity;
    }

    @GET
    public ConfigView get() {
        boolean session = !identity.isAnonymous();
        return new ConfigView(
                observabilite.sentry().dsn().orElse(""),
                observabilite.sentry().environment(),
                observabilite.cloudflare().webAnalyticsToken().orElse(""),
                session ? devMode.isActive() : null,
                session ? version : null,
                session ? mailMock : null,
                oidc.enabled(),
                secours.open(oidc.enabled()));
    }

    /**
     * @param sentryDsn          empty disables Sentry/Bugsink error reporting
     * @param sentryEnvironment  tag attached to every reported error/transaction
     * @param cloudflareWebAnalyticsToken empty disables Cloudflare Web Analytics
     * @param devMode            absent without a session; server launched with {@code quarkus:dev}: the
     *                           Dev UI exists at {@code /q/dev-ui}, so the
     *                           interface may link to it
     * @param version            absent without a session; backend version: {@code X.Y.Z} on a release
     *                           image, the short SHA on a recette image,
     *                           {@code 999-SNAPSHOT} in a local build
     * @param mailMock           absent without a session; the mailer is mocked: a mail recorded as sent
     *                           never left the server
     * @param authOidc           a provider signs people in: the login page offers its button
     * @param authSecours        the form login of the {@code admin} account is open: the
     *                           login page offers its password form
     */
    @Schema(requiredProperties = {"authOidc", "authSecours"})
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ConfigView(
            String sentryDsn,
            String sentryEnvironment,
            String cloudflareWebAnalyticsToken,
            Boolean devMode,
            String version,
            Boolean mailMock,
            boolean authOidc,
            boolean authSecours) {}
}
