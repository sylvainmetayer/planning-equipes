package dev.sylvain.planning.mcp;

import dev.sylvain.planning.config.ConfigMcp;
import dev.sylvain.planning.config.ConfigOidc;
import dev.sylvain.planning.service.mcp.McpKeyService;
import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.AuthenticationRequest;
import io.quarkus.security.identity.request.TrustedAuthenticationRequest;
import io.quarkus.vertx.http.runtime.security.ChallengeData;
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;

/**
 * Header-based authentication for the MCP endpoints (issue #107: "permettre
 * de définir des headers HTTP [...] et possibilité d'ajouter un MDP via
 * header basic auth par exemple"). There is no user/session model in this
 * application (see {@code ConstraintResource.ConstraintToggleUpdate}'s
 * javadoc), so a single shared API key compared against a configurable
 * header is the simplest fit. Since ADR 0097 the same transport is also an
 * OAuth2 resource server (the {@code mcptransport} OIDC tenant) when a provider
 * is configured; the key stays for the clients that cannot do OAuth.
 *
 * <p>Implemented as a Quarkus {@link HttpAuthenticationMechanism} — paired
 * with {@code quarkus.http.auth.permission.mcp.paths=/mcp/*} in
 * application.properties and {@link McpApiKeyIdentityProvider} — rather than
 * a global HTTP {@code Filter} bean, because a filter has no way to say
 * "this path needs the {@code mcp} role": it would have to re-implement the
 * check, and then answer its own challenge. The
 * {@code quarkus.http.auth.permission.*} policy engine says it once, and gates
 * every path uniformly regardless of which extension mounted it.
 *
 * <p>The reason this javadoc used to give — that
 * {@code quarkus-mcp-server-http} registers its routes ahead of the Vert.x
 * filter chain, so a filter never sees them — does not hold, and was already
 * contradicted by the tree it was written in: the headers
 * {@code SecurityHeadersFilter} writes are on every {@code /mcp} response.
 * {@code McpRateLimiter} is a filter on these very routes, and it refuses
 * before this mechanism is ever consulted. What is written above is the reason
 * that does hold.
 *
 * <p>Secure by default: with no {@code planning.mcp.api-key} configured,
 * {@link #authenticate} can never succeed, so every request under
 * {@code /mcp} is rejected (401) rather than served without authentication —
 * the MCP tools can start a solver run and read animateur data, so silently
 * exposing them unauthenticated would be worse than refusing to serve them.
 */
@ApplicationScoped
public class McpApiKeyAuthenticationMechanism implements HttpAuthenticationMechanism {

    static final String PRINCIPAL = "mcp";

    /** {@code AuthResource#oidcLogin} and the logout, which must be answered a redirect, not a 401. */
    private static final String CHEMIN_CONNEXION_OIDC = "/api/auth/oidc/";

    private final ConfigMcp config;

    private final ConfigOidc oidc;

    private final McpKeyService keys;

    @Inject
    McpApiKeyAuthenticationMechanism(ConfigMcp config, ConfigOidc oidc, McpKeyService keys) {
        this.config = config;
        this.oidc = oidc;
        this.keys = keys;
    }

    @Override
    public Uni<SecurityIdentity> authenticate(RoutingContext context, IdentityProviderManager identityProviderManager) {
        if (!isMcpTransport(context.normalizedPath())) {
            return Uni.createFrom().nullItem();
        }
        String presented = presentedKey(context);
        if (presented == null || !requiredHeadersPresent(context)) {
            return Uni.createFrom().nullItem();
        }
        // The keys live in the database (ADR 0097): read on a worker, never on the event loop.
        return Uni.createFrom()
                .item(() -> keys.accepts(presented))
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .chain(accepted -> accepted
                        ? identityProviderManager.authenticate(new TrustedAuthenticationRequest(PRINCIPAL))
                        : Uni.createFrom().nullItem());
    }

    /**
     * Additional, opt-in {@code Nom-Header=valeur} pairs that must accompany the API key —
     * for a deployment sitting behind a reverse proxy that identifies callers
     * by a custom header (Pangolin's {@code P-Access-Token-Id} /
     * {@code P-Access-Token}, for instance). Empty by default, so an ordinary
     * deployment is unaffected; when set, the origin stays protected even if
     * someone reaches it without going through the proxy. Only enable it for
     * headers the proxy <em>forwards</em>: a proxy that consumes and strips
     * them would make every request fail here.
     *
     * <p>Fails closed like the API key itself: an entry with no {@code =}, or
     * a blank expected value, can never be matched, rather than degrading to
     * "header optional".
     */
    private boolean requiredHeadersPresent(RoutingContext context) {
        if (config.requiredHeaders().isEmpty()) {
            return true;
        }
        return config.requiredHeaders().get().stream().allMatch(paire -> {
            int separateur = paire.indexOf('=');
            String nom = separateur < 0
                    ? paire.trim()
                    : paire.substring(0, separateur).trim();
            String valeurAttendue = separateur < 0 ? "" : paire.substring(separateur + 1);
            return keysEqual(valeurAttendue, context.request().getHeader(nom));
        });
    }

    /**
     * A bare 401 everywhere - except where another mechanism has something to
     * say that a client needs: {@code /mcp} with OIDC on (an MCP client finds its
     * authorization server in the {@code resource_metadata} of the 401's
     * {@code WWW-Authenticate}, RFC 9728) and the OIDC login route (a redirect).
     * Conditioned on OIDC being on: with it off, standing aside would hand the
     * route to form auth, whose challenge is the 302 issue #165 exists to prevent.
     */
    @Override
    public Uni<ChallengeData> getChallenge(RoutingContext context) {
        String path = context.normalizedPath();
        boolean cedeLaMain = oidc.enabled() && (path.startsWith(CHEMIN_CONNEXION_OIDC) || isMcpTransport(path));
        return cedeLaMain ? Uni.createFrom().nullItem() : Uni.createFrom().item(new ChallengeData(401));
    }

    /**
     * The transport and nothing that merely starts like it: the normalized path,
     * compared exactly or as a parent, the way {@code McpTenantResolver} does.
     */
    public static boolean isMcpTransport(String path) {
        return path != null && (path.equals("/mcp") || path.startsWith("/mcp/"));
    }

    /**
     * Above the form mechanism (priority 1000, see
     * {@code quarkus.http.auth.form.*}, issue #165), so the challenge served
     * to an unauthenticated request is this plain 401 everywhere — an API/SPA
     * client must never be answered with form auth's 302 to an HTML login
     * page, whether it targets {@code /mcp} or {@code /api}. Authentication
     * itself is unaffected: this mechanism ignores any request outside
     * {@code /mcp} (see {@link #authenticate}), and the form mechanism keeps
     * handling its cookie and {@code /j_security_check}.
     */
    @Override
    public int getPriority() {
        return 2000;
    }

    @Override
    public Set<Class<? extends AuthenticationRequest>> getCredentialTypes() {
        return Set.of(TrustedAuthenticationRequest.class);
    }

    /** Constant-time comparison so a mistyped key can't be brute-forced via response-time measurement. */
    private static boolean keysEqual(String attendue, String presentee) {
        if (presentee == null) {
            return false;
        }
        return MessageDigest.isEqual(
                attendue.getBytes(StandardCharsets.UTF_8), presentee.getBytes(StandardCharsets.UTF_8));
    }

    private String presentedKey(RoutingContext context) {
        return presentedKey(context, config.apiKeyHeader());
    }

    /**
     * The key a request presents, or {@code null} when it carries none — the
     * dedicated header first, then {@code Authorization: Bearer}.
     *
     * <p>Public for {@code McpRateLimiter}, which has to tell a request that
     * <em>guessed</em> a key from one that carried none: only the first is an
     * attempt worth counting against its lockout, and a client not yet
     * configured would otherwise lock itself out by probing. Reading the two
     * headers a second time over there is the copy that would drift the day
     * this mechanism learns a third form.</p>
     */
    public static String presentedKey(RoutingContext context, String headerName) {
        return presented(context, headerName);
    }

    /**
     * The key a request presents <em>as a guess</em>: {@link #presentedKey}
     * minus an {@code Authorization: Bearer} shaped like a JWT (three
     * dot-separated segments). An MCP client sends its OAuth access token
     * there, and counting that against the key lockout would lock an address
     * out for an expired token. A key is a random string, never a JWT.
     */
    public static String presentedKeyAttempt(RoutingContext context, String headerName) {
        String presented = presented(context, headerName);
        if (presented != null
                && context.request().getHeader(headerName) == null
                && presented.chars().filter(c -> c == '.').count() == 2) {
            return null;
        }
        return presented;
    }

    private static String presented(RoutingContext context, String headerName) {
        String header = context.request().getHeader(headerName);
        if (header != null) {
            return header;
        }
        String authorization = context.request().getHeader("Authorization");
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return authorization.substring(7).trim();
        }
        return null;
    }
}
