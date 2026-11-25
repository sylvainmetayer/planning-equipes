package dev.sylvain.planning.api;

import dev.sylvain.planning.service.compte.Compte;
import dev.sylvain.planning.service.compte.CompteAccesService;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.IdentityProvider;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.UsernamePasswordAuthenticationRequest;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
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
 * of that address answers {@code null}, which hands the request to the next
 * provider — the break-glass {@code admin} — and an exception would not.</p>
 *
 * <p>The identity carries no role yet: {@link CompteIdentityAugmentor} binds the
 * account and its rights, here and on every later request.</p>
 */
@ApplicationScoped
public class LocalSignInIdentityProvider implements IdentityProvider<UsernamePasswordAuthenticationRequest> {

    @Inject
    CompteAccesService acces;

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
        return context.runBlocking(() -> {
            String secret = new String(request.getPassword().getPassword());
            Compte compte = acces.redeem(request.getUsername(), secret).orElse(null);
            if (compte == null) {
                // Not an exception: Quarkus moves to the next provider on a null identity only.
                return null;
            }
            return QuarkusSecurityIdentity.builder()
                    .setPrincipal(new QuarkusPrincipal(compte.email()))
                    .addAttribute(Compte.LOCAL_ATTRIBUTE, Boolean.TRUE)
                    .build();
        });
    }
}
