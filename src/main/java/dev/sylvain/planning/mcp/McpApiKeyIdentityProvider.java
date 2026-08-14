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
 * Builds the {@link SecurityIdentity} for a request {@link McpApiKeyAuthenticationMechanism}
 * has already validated (that's the whole point of {@link TrustedAuthenticationRequest}: the
 * key check already happened upstream, this provider only assembles the identity).
 */
@ApplicationScoped
public class McpApiKeyIdentityProvider implements IdentityProvider<TrustedAuthenticationRequest> {

    @Override
    public Class<TrustedAuthenticationRequest> getRequestType() {
        return TrustedAuthenticationRequest.class;
    }

    @Override
    public Uni<SecurityIdentity> authenticate(TrustedAuthenticationRequest request, AuthenticationRequestContext context) {
        return Uni.createFrom().item(QuarkusSecurityIdentity.builder()
                .setPrincipal(new QuarkusPrincipal(request.getPrincipal()))
                .build());
    }
}
