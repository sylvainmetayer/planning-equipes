package dev.sylvain.planning.mcp;

import dev.sylvain.planning.service.compte.Compte;
import dev.sylvain.planning.service.espace.RemoteUserAuthentication;
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
 * <p>The {@code mcp} role is load-bearing: the {@code /mcp} policy is
 * {@code roles-allowed=mcp}, not {@code authenticated}. The latter was also
 * met by the admin's session cookie — form auth reads it on every path — so
 * « the MCP endpoint needs the API key » was not true for a logged-in
 * browser. Only the principal {@link McpApiKeyAuthenticationMechanism}
 * vouches for gets that role; the admin one gets its own, which the API
 * policies do not check today but the first {@code @RolesAllowed} would.</p>
 */
@ApplicationScoped
public class McpApiKeyIdentityProvider implements IdentityProvider<TrustedAuthenticationRequest> {

    /** Matches {@code quarkus.security.users.embedded.roles.admin}, so both login paths grant the same role. */
    static final String ROLE_ADMIN = "admin";

    /** Matches {@code quarkus.http.auth.policy.cle-mcp.roles-allowed}. */
    public static final String ROLE_MCP = "mcp";

    @Override
    public Class<TrustedAuthenticationRequest> getRequestType() {
        return TrustedAuthenticationRequest.class;
    }

    @Override
    public Uni<SecurityIdentity> authenticate(
            TrustedAuthenticationRequest request, AuthenticationRequestContext context) {
        QuarkusSecurityIdentity.Builder identite =
                QuarkusSecurityIdentity.builder().setPrincipal(new QuarkusPrincipal(request.getPrincipal()));
        if (RemoteUserAuthentication.REQUEST_PRINCIPAL.equals(request.getPrincipal())) {
            // The proxy's admin, marked: CompteIdentityAugmentor must tell it
            // from a form session of the same name, which it strips of its
            // role once the break-glass door is closed.
            identite.setPrincipal(new QuarkusPrincipal(RemoteUserAuthentication.PRINCIPAL_ADMIN))
                    .addRole(ROLE_ADMIN)
                    .addAttribute(RemoteUserAuthentication.IDENTITY_ATTRIBUTE, Boolean.TRUE);
        } else if (RemoteUserAuthentication.PRINCIPAL_ADMIN.equals(request.getPrincipal())) {
            identite.addRole(ROLE_ADMIN);
        } else if (McpApiKeyAuthenticationMechanism.PRINCIPAL.equals(request.getPrincipal())) {
            identite.addRole(ROLE_MCP);
        } else if (request.getPrincipal().indexOf('@') >= 1) {
            // The session cookie of a local account, which names the address it
            // was opened for: CompteIdentityAugmentor binds the account (and
            // withdraws the session if it was deactivated since).
            identite.addAttribute(Compte.LOCAL_ATTRIBUTE, Boolean.TRUE);
        }
        return Uni.createFrom().item(identite.build());
    }
}
