package dev.sylvain.planning.service.espace;

import dev.sylvain.planning.config.ConfigOidc;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.security.Principal;
import java.util.Locale;
import java.util.Optional;
import org.eclipse.microprofile.jwt.Claims;
import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * What a session with the instance's OIDC provider says about the person
 * holding it, for the one question the espace animateur asks: which e-mail
 * address has this visitor proved they control?
 *
 * <p>The six-digit code mailed to the fiche answers it for everybody; a
 * provider session answers it too, for an animateur who also has a directory
 * account (a council's staff on their own event), and the espace then opens
 * without the code. The address must still be the fiche's (see
 * {@code SessionEspaceFilter}): one directory account is one person, who may
 * hold a fiche in several editions, so the session says who is knocking and
 * the URL token which espace opens.</p>
 *
 * <p>Inert when OIDC is off: outside it the principal is a
 * {@code QuarkusPrincipal} carrying no claims at all.</p>
 */
@ApplicationScoped
public class OidcAuthentication {

    @Inject
    ConfigOidc config;

    @Inject
    SecurityIdentity identity;

    public boolean actif() {
        return config.enabled();
    }

    /**
     * The address of the current provider session, or empty when there is
     * none.
     *
     * <p>An unverified address is refused unless the instance said its
     * provider's addresses are authoritative
     * ({@code planning.auth.oidc.require-email-verified=false}, an ADFS): a
     * provider that lets anyone register an address would otherwise let them
     * name any animateur's and walk into their espace.</p>
     */
    public Optional<String> trustedEmail() {
        if (!config.enabled() || identity == null || identity.isAnonymous()) {
            return Optional.empty();
        }
        Principal principal = identity.getPrincipal();
        if (!(principal instanceof JsonWebToken token)) {
            return Optional.empty();
        }
        if (config.requireEmailVerified() && !Boolean.TRUE.equals(token.getClaim(Claims.email_verified))) {
            return Optional.empty();
        }
        String email = token.getClaim(Claims.email);
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(normalize(email));
    }

    /** Addresses are compared case-insensitively and trimmed, as everywhere else. */
    public static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
