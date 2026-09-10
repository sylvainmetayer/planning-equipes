package dev.sylvain.planning.api;

import java.util.Set;

import dev.sylvain.planning.service.espace.RemoteUserAuthentication;
import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.AuthenticationRequest;
import io.quarkus.security.identity.request.TrustedAuthenticationRequest;
import io.quarkus.vertx.http.runtime.security.ChallengeData;
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Grants the admin role to the single address configured as
 * {@code planning.auth.remote-user.admin-email}, when an access proxy asserts it
 * through the trusted headers (see {@link RemoteUserAuthentication}).
 *
 * <p>Strictly additive: the mode is off by default, and even when on this
 * mechanism returns "no identity" for anything it does not recognise, so the
 * form login keeps working unchanged — an operator can always fall back to
 * {@code /login} if the proxy is bypassed or misconfigured. That is why it
 * never issues a challenge of its own either: the 401 an unauthenticated
 * request gets keeps coming from the MCP mechanism, which the SPA already
 * turns into a redirect to the login page.</p>
 *
 * <p>Priority sits between the MCP key (2000) and form auth (1000): a request
 * to {@code /mcp} must still be judged by its API key, and a valid session
 * cookie must not be shadowed — but a proxy-asserted admin has to be
 * recognised before the form mechanism concludes "anonymous".</p>
 *
 * <p>Animateurs are <b>not</b> handled here. They authenticate against the
 * espace-animateur guard ({@code SessionEspaceFilter}), where the token in
 * the URL has already designated an edition and a person: matching the
 * asserted address against that person's fiche is meaningful, whereas
 * granting a global identity to an address that may exist in several
 * editions is not.</p>
 */
@ApplicationScoped
public class RemoteUserAuthenticationMechanism implements HttpAuthenticationMechanism {

    @Inject
    RemoteUserAuthentication remoteUser;

    @Override
    public Uni<SecurityIdentity> authenticate(RoutingContext context, IdentityProviderManager identityProviderManager) {
        if (!remoteUser.actif() || context.request().path().startsWith("/mcp")) {
            return Uni.createFrom().nullItem();
        }
        return remoteUser.trustedEmail(nom -> context.request().getHeader(nom))
                .filter(remoteUser::isAdmin)
                .map(email -> identityProviderManager
                        .authenticate(new TrustedAuthenticationRequest(RemoteUserAuthentication.PRINCIPAL_ADMIN)))
                .orElseGet(() -> Uni.createFrom().nullItem());
    }

    /**
     * Never challenges: {@code null} defers to the next mechanism, so an
     * unauthenticated caller keeps getting the plain 401 the SPA expects
     * instead of a redirect this mode has no login page to point at.
     */
    @Override
    public Uni<ChallengeData> getChallenge(RoutingContext context) {
        return Uni.createFrom().nullItem();
    }

    @Override
    public int getPriority() {
        return 1500;
    }

    @Override
    public Set<Class<? extends AuthenticationRequest>> getCredentialTypes() {
        return Set.of(TrustedAuthenticationRequest.class);
    }
}
