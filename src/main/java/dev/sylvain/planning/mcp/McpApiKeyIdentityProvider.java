package dev.sylvain.planning.mcp;

import dev.sylvain.planning.service.compte.Compte;
import dev.sylvain.planning.service.compte.SessionOpenings;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.IdentityProvider;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.TrustedAuthenticationRequest;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.quarkus.vertx.http.runtime.security.HttpSecurityUtils;
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.Cookie;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Builds the {@link SecurityIdentity} for a request an upstream mechanism has
 * already validated (that's the whole point of {@link TrustedAuthenticationRequest}:
 * the credential check already happened, this provider only assembles the identity).
 *
 * <p>Serves <b>every</b> mechanism of the application producing a trusted
 * request — {@link McpApiKeyAuthenticationMechanism} and the form session. Deliberately one
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
 *
 * <p>A form session that was logged out ({@link SessionOpenings#loggedOut}) keeps
 * its principal but no role, marked stale like a local session whose password
 * changed, and its cookie is expired in the answer instead of renewed: the
 * cookie is stateless, so this is the only place a logout can be refused.</p>
 */
@ApplicationScoped
public class McpApiKeyIdentityProvider implements IdentityProvider<TrustedAuthenticationRequest> {

    /** Principal name of the form login's admin. */
    static final String PRINCIPAL_ADMIN = "admin";

    /** Matches {@code quarkus.security.users.embedded.roles.admin}, so both login paths grant the same role. */
    static final String ROLE_ADMIN = "admin";

    /** Matches {@code quarkus.http.auth.policy.cle-mcp.roles-allowed}. */
    public static final String ROLE_MCP = "mcp";

    private final SessionOpenings openings;

    private final String sessionCookie;

    @Inject
    public McpApiKeyIdentityProvider(
            SessionOpenings openings,
            @ConfigProperty(name = "quarkus.http.auth.form.cookie-name") String sessionCookie) {
        this.openings = openings;
        this.sessionCookie = sessionCookie;
    }

    /**
     * Ahead of the embedded {@code admin}'s trusted provider (default priority
     * 1001), which would otherwise restore the admin's cookie without the
     * logout check — the order between them was left to chance.
     */
    @Override
    public int priority() {
        return 2000;
    }

    @Override
    public Class<TrustedAuthenticationRequest> getRequestType() {
        return TrustedAuthenticationRequest.class;
    }

    @Override
    public Uni<SecurityIdentity> authenticate(
            TrustedAuthenticationRequest request, AuthenticationRequestContext context) {
        QuarkusSecurityIdentity.Builder identite =
                QuarkusSecurityIdentity.builder().setPrincipal(new QuarkusPrincipal(request.getPrincipal()));
        if (!McpApiKeyAuthenticationMechanism.PRINCIPAL.equals(request.getPrincipal())
                && request.getAttribute(HttpSecurityUtils.ROUTING_CONTEXT_ATTRIBUTE) instanceof RoutingContext routing
                && loggedOut(routing)) {
            // Replaces the renewed cookie the form mechanism is about to set.
            routing.addHeadersEndHandler(v -> routing.response()
                    .addCookie(Cookie.cookie(sessionCookie, "")
                            .setPath("/")
                            .setMaxAge(0)
                            .setHttpOnly(true)));
            return Uni.createFrom()
                    .item(identite.addAttribute(Compte.STALE_ATTRIBUTE, Boolean.TRUE)
                            .build());
        }
        if (PRINCIPAL_ADMIN.equals(request.getPrincipal())) {
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

    private boolean loggedOut(RoutingContext routing) {
        Cookie cookie = routing.request().getCookie(sessionCookie);
        return cookie != null && openings.loggedOut(cookie.getValue());
    }
}
