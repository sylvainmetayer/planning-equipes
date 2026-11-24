package dev.sylvain.planning.api;

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
 * Builds the {@code admin} identity of the opt-in remote-user mode for the
 * request {@link RemoteUserAuthenticationMechanism} vouched for, and marks it:
 * {@code CompteIdentityAugmentor} must tell it from a form session of the
 * same name, which it strips of its role once the local door is closed.
 */
@ApplicationScoped
public class RemoteUserIdentityProvider implements IdentityProvider<TrustedAuthenticationRequest> {

    @Override
    public Class<TrustedAuthenticationRequest> getRequestType() {
        return TrustedAuthenticationRequest.class;
    }

    @Override
    public Uni<SecurityIdentity> authenticate(
            TrustedAuthenticationRequest request, AuthenticationRequestContext context) {
        if (!RemoteUserAuthentication.REQUEST_PRINCIPAL.equals(request.getPrincipal())) {
            return Uni.createFrom().nullItem();
        }
        return Uni.createFrom()
                .item(QuarkusSecurityIdentity.builder()
                        .setPrincipal(new QuarkusPrincipal(RemoteUserAuthentication.PRINCIPAL_ADMIN))
                        .addRole(CompteIdentityAugmentor.ROLE_ADMIN)
                        .addAttribute(RemoteUserAuthentication.IDENTITY_ATTRIBUTE, Boolean.TRUE)
                        .build());
    }
}
