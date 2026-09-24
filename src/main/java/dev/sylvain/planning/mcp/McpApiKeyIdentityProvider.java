package dev.sylvain.planning.mcp;

import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.IdentityProvider;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.TrustedAuthenticationRequest;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Builds the {@link SecurityIdentity} for a request an upstream mechanism has
 * already validated (that's the whole point of {@link TrustedAuthenticationRequest}:
 * the credential check already happened, this provider only assembles the identity).
 *
 * <p>Serves {@link McpApiKeyAuthenticationMechanism}, the one mechanism of
 * the application producing a trusted request. The role below is attached
 * from the principal it vouches for.</p>
 *
 * <p>The {@code mcp} role is load-bearing: the {@code /mcp} policy is
 * {@code roles-allowed=mcp}, not {@code authenticated}. The latter was also
 * met by the admin's session cookie — form auth reads it on every path — so
 * « the MCP endpoint needs the API key » was not true for a logged-in
 * browser. Only the principal {@link McpApiKeyAuthenticationMechanism}
 * vouches for gets that role — and a Keycloak token carrying the realm role of
 * the same name, which is why the string below is a contract shared with
 * {@code docker/keycloak/realm-planning.json}.</p>
 */
@ApplicationScoped
public class McpApiKeyIdentityProvider implements IdentityProvider<TrustedAuthenticationRequest> {

    /** Matches {@code quarkus.http.auth.policy.cle-mcp.roles-allowed}. */
    static final String ROLE_MCP = "mcp";

    @Override
    public Class<TrustedAuthenticationRequest> getRequestType() {
        return TrustedAuthenticationRequest.class;
    }

    @Override
    public Uni<SecurityIdentity> authenticate(
            TrustedAuthenticationRequest request, AuthenticationRequestContext context) {
        QuarkusSecurityIdentity.Builder identite =
                QuarkusSecurityIdentity.builder().setPrincipal(new QuarkusPrincipal(request.getPrincipal()));
        if (McpApiKeyAuthenticationMechanism.PRINCIPAL.equals(request.getPrincipal())) {
            identite.addRole(ROLE_MCP);
        }
        return Uni.createFrom().item(identite.build());
    }
}
