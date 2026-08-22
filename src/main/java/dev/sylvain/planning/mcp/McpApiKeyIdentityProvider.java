package dev.sylvain.planning.mcp;

import dev.sylvain.planning.service.RemoteUserAuthentication;
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
 * <p>Serves <b>every</b> mechanism of the application producing a trusted
 * request — {@link McpApiKeyAuthenticationMechanism} and, when the mode is
 * enabled, {@code RemoteUserAuthenticationMechanism}. Deliberately one
 * provider rather than one per mechanism: Quarkus resolves providers by
 * request type, so two of them registered for
 * {@link TrustedAuthenticationRequest} would compete for the same requests.
 * The principal is what distinguishes the callers, and the role below is
 * attached from it.</p>
 *
 * <p>Every {@code quarkus.http.auth.permission.*} policy of this application
 * is {@code authenticated}, never {@code roles-allowed}, so the role is not
 * load-bearing today. It is set anyway: an identity that says who it is
 * without saying what it may do would make the first {@code @RolesAllowed}
 * added here fail in a way nobody would connect back to this class.</p>
 */
@ApplicationScoped
public class McpApiKeyIdentityProvider implements IdentityProvider<TrustedAuthenticationRequest> {

    /** Matches {@code quarkus.security.users.embedded.roles.admin}, so both login paths grant the same role. */
    static final String ROLE_ADMIN = "admin";

    @Override
    public Class<TrustedAuthenticationRequest> getRequestType() {
        return TrustedAuthenticationRequest.class;
    }

    @Override
    public Uni<SecurityIdentity> authenticate(TrustedAuthenticationRequest request, AuthenticationRequestContext context) {
        QuarkusSecurityIdentity.Builder identite = QuarkusSecurityIdentity.builder()
                .setPrincipal(new QuarkusPrincipal(request.getPrincipal()));
        if (RemoteUserAuthentication.PRINCIPAL_ADMIN.equals(request.getPrincipal())) {
            identite.addRole(ROLE_ADMIN);
        }
        return Uni.createFrom().item(identite.build());
    }
}
