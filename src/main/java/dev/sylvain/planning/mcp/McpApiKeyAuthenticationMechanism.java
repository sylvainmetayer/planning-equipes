package dev.sylvain.planning.mcp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.Set;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.AuthenticationRequest;
import io.quarkus.security.identity.request.TrustedAuthenticationRequest;
import io.quarkus.vertx.http.runtime.security.ChallengeData;
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Header-based authentication for the MCP endpoints (issue #107: "permettre
 * de définir des headers HTTP [...] et possibilité d'ajouter un MDP via
 * header basic auth par exemple"). There is no user/session model in this
 * application (see {@code ConstraintResource.ConstraintToggleUpdate}'s
 * javadoc), so a single shared API key compared against a configurable
 * header is the simplest fit — full OAuth2 would need a user/consent model
 * this app doesn't have.
 *
 * <p>Implemented as a Quarkus {@link HttpAuthenticationMechanism} — paired
 * with {@code quarkus.http.auth.permission.mcp.paths=/mcp/*} in
 * application.properties and {@link McpApiKeyIdentityProvider} — rather than
 * a global HTTP {@code Filter} bean, because {@code quarkus-mcp-server-http}
 * registers its routes ahead of the ordinary Vert.x filter chain: a
 * {@code Filter} bean simply never sees requests it handles. The
 * {@code quarkus.http.auth.permission.*} policy engine, by contrast, gates
 * every path uniformly regardless of which extension mounted it.
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

    @ConfigProperty(name = "planning.mcp.api-key")
    Optional<String> apiKey;

    @ConfigProperty(name = "planning.mcp.api-key-header", defaultValue = "X-MCP-Api-Key")
    String headerName;

    @Override
    public Uni<SecurityIdentity> authenticate(RoutingContext context, IdentityProviderManager identityProviderManager) {
        if (!context.request().path().startsWith("/mcp")) {
            return Uni.createFrom().nullItem();
        }
        if (apiKey.isEmpty() || apiKey.get().isBlank() || !clesEgales(apiKey.get(), clePresentee(context))) {
            return Uni.createFrom().nullItem();
        }
        return identityProviderManager.authenticate(new TrustedAuthenticationRequest(PRINCIPAL));
    }

    @Override
    public Uni<ChallengeData> getChallenge(RoutingContext context) {
        return Uni.createFrom().item(new ChallengeData(401));
    }

    @Override
    public Set<Class<? extends AuthenticationRequest>> getCredentialTypes() {
        return Set.of(TrustedAuthenticationRequest.class);
    }

    /** Constant-time comparison so a mistyped key can't be brute-forced via response-time measurement. */
    private static boolean clesEgales(String attendue, String presentee) {
        if (presentee == null) {
            return false;
        }
        return MessageDigest.isEqual(attendue.getBytes(StandardCharsets.UTF_8), presentee.getBytes(StandardCharsets.UTF_8));
    }

    private String clePresentee(RoutingContext context) {
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
