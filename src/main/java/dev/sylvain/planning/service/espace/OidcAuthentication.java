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
 * What a Keycloak session says about the person holding it, for the one
 * question the espace animateur asks: which e-mail address has this visitor
 * proved they control?
 *
 * <p>It is the question the six-digit code mailed to the fiche used to answer
 * (ADR 0049 removed it): a verified address in a Keycloak session establishes
 * the same fact, with a second factor the realm can impose, and one way into
 * the espace instead of two whose weaker one would set the security level.</p>
 *
 * <p>Inert when OIDC is off (the break-glass mode), and not by a flag alone:
 * outside Keycloak the principal is a {@code QuarkusPrincipal} carrying no
 * claims at all, so {@link #trustedEmail()} has nothing to return.</p>
 *
 * <p>Two conditions, and neither is sufficient alone. The session must carry
 * the <b>animateur role</b> ({@code planning.auth.oidc.animateur-role}), which
 * is not the realm's ordinary {@code user} role and never will be: a privilege
 * built on "this person exists" is a privilege every future account inherits
 * without anyone granting it. And the address must then match the fiche the
 * URL token designates (see {@code SessionEspaceFilter}) — one Keycloak
 * account is one person, and one person may hold a fiche in several editions,
 * so the account says who is knocking, never which espace opens.</p>
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
     * The verified e-mail address of the current Keycloak session, or empty
     * when there is none.
     *
     * <p>An unverified address is refused. Keycloak is perfectly willing to
     * hold an account whose {@code email} nobody ever confirmed — self
     * registration, a bulk import, an admin typo — and accepting one here
     * would let anyone who can create such an account name any animateur's
     * address and walk into their espace. {@code email_verified} is the claim
     * that says a mailbox round trip happened, which is precisely the proof
     * the code screen used to perform itself.</p>
     */
    public Optional<String> trustedEmail() {
        if (!config.enabled() || identity == null || identity.isAnonymous()) {
            return Optional.empty();
        }
        if (!identity.hasRole(config.animateurRole())) {
            // Signed in is not the same as entitled. The realm's ordinary
            // "user" role — which every account carries — opens nothing here:
            // being an animateur is something a realm administrator granted,
            // so that the next role added to the realm arrives with no access
            // rather than with this one.
            return Optional.empty();
        }
        Principal principal = identity.getPrincipal();
        if (!(principal instanceof JsonWebToken token)) {
            return Optional.empty();
        }
        Boolean verified = token.getClaim("email_verified");
        if (!Boolean.TRUE.equals(verified)) {
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
