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
 * <p>Serves {@link McpApiKeyAuthenticationMechanism} and nothing else: any
 * other trusted request — the break-glass form session read back from its
 * cookie — is left to the provider that owns it.</p>
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
        if (!McpApiKeyAuthenticationMechanism.PRINCIPAL.equals(request.getPrincipal())) {
            // Not ours: form auth reads its session cookie back as a trusted
            // request too, for the break-glass `admin`, and the embedded realm's
            // provider is the one that knows that account's roles. Answering
            // here would build an identity with none — and which provider
            // Quarkus asks first is not an order this class can rely on (it
            // differed between a laptop and CI). A null item hands the request
            // to the next provider.
            return Uni.createFrom().nullItem();
        }
        return Uni.createFrom()
                .item(QuarkusSecurityIdentity.builder()
                        .setPrincipal(new QuarkusPrincipal(request.getPrincipal()))
                        .addRole(ROLE_MCP)
                        .build());
    }
}
