package dev.sylvain.planning.api;

import dev.sylvain.planning.service.compte.Compte;
import dev.sylvain.planning.service.compte.CompteAccesService;
import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.IdentityProvider;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.UsernamePasswordAuthenticationRequest;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.quarkus.vertx.http.runtime.security.HttpSecurityUtils;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The form login's second reader, beside the embedded {@code admin} account's.
 *
 * <p>What the login form carries for a local account is not the password — that
 * was checked, with the mailed code, by {@code POST /api/auth/connexion} — but
 * the one-time challenge it earned ({@code defi} or {@code defi:code}). Posting
 * it through the form mechanism is what buys the session cookie, the login
 * lock and the login journal for free. Anything that is not a live challenge
 * of that address fails the local path outright. On the break-glass path this
 * provider steps aside ({@code null}: Quarkus moves to the next provider on a
 * null identity only, an exception would end the chain) and never redeems.</p>
 *
 * <p>The identity carries no role yet: {@link CompteIdentityAugmentor} binds the
 * account and its rights, here and on every later request.</p>
 */
@ApplicationScoped
public class LocalSignInIdentityProvider implements IdentityProvider<UsernamePasswordAuthenticationRequest> {

    private final CompteAccesService acces;

    @Inject
    public LocalSignInIdentityProvider(CompteAccesService acces) {
        this.acces = acces;
    }

    /**
     * Before the embedded {@code admin}'s provider, whose default priority is
     * 1001 and which answers an unknown user with an <i>exception</i>, ending
     * the chain: the order between equals is otherwise left to chance.
     */
    @Override
    public int priority() {
        return 2000;
    }

    @Override
    public Class<UsernamePasswordAuthenticationRequest> getRequestType() {
        return UsernamePasswordAuthenticationRequest.class;
    }

    @Override
    public Uni<SecurityIdentity> authenticate(
            UsernamePasswordAuthenticationRequest request, AuthenticationRequestContext context) {
        Object routing = request.getAttribute(HttpSecurityUtils.ROUTING_CONTEXT_ATTRIBUTE);
        String path = routing instanceof RoutingContext r ? r.normalizedPath() : null;
        boolean local = AdminLoginLimiter.isLocalLoginPath(path);
        if (!local) {
            // The break-glass path never redeems a challenge: what is posted there is
            // the configuration account's business, and a challenge there would be
            // journalled as a break-glass login.
            return Uni.createFrom().nullItem();
        }
        return context.runBlocking(() -> {
            String secret = new String(request.getPassword().getPassword());
            Compte compte = acces.redeem(request.getUsername(), secret).orElse(null);
            if (compte == null) {
                // The local path takes challenges and nothing else — not the admin's
                // password, which would be an oracle on a door closed on purpose.
                throw new AuthenticationFailedException();
            }
            return QuarkusSecurityIdentity.builder()
                    .setPrincipal(new QuarkusPrincipal(acces.sessionPrincipal(compte)))
                    .addAttribute(Compte.LOCAL_ATTRIBUTE, Boolean.TRUE)
                    .build();
        });
    }
}
