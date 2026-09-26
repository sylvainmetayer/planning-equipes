package dev.sylvain.planning.api;

import dev.sylvain.planning.config.ConfigSecours;
import dev.sylvain.planning.mcp.McpTenantResolver;
import dev.sylvain.planning.service.compte.Compte;
import dev.sylvain.planning.service.compte.CompteService;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.SecurityIdentityAugmentor;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Set;
import org.eclipse.microprofile.jwt.Claims;
import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * Joins the identity Quarkus built to the account this application keeps for
 * the person (ADR 0054), on every authenticated request.
 *
 * <ul>
 *   <li><b>A Keycloak session with a verified address</b> is attached to its
 *       {@code compte}, created on the first sign-in. A deactivated account
 *       keeps its name and loses every role — the kill switch an organiser
 *       pulls without the Keycloak console. An active one gains the roles its
 *       edition-wide rights grant, on top of the realm roles.</li>
 *   <li><b>A form session while the break-glass door is closed</b> loses its
 *       role: {@link FormLoginSecours} stops new logins, and a cookie issued
 *       while the door was open must not outlive the incident by eight
 *       hours.</li>
 *   <li><b>A browser token without a verified address</b> loses every role:
 *       it cannot be tied to a {@code compte}, so it could not be switched
 *       off either — an account created in the console with
 *       {@code email_verified=false} must not keep {@code admin} out of the
 *       organiser's reach.</li>
 *   <li><b>A token of the MCP transport tenant</b> — a client-credentials
 *       client, which is no person and has no address — keeps the
 *       {@code mcp} role and nothing else.</li>
 *   <li>Anonymous and the MCP API key pass through untouched.</li>
 * </ul>
 *
 * <p>The account is read on a worker thread ({@code runBlocking}): the
 * augmentor runs on the event loop, and JDBC must not.</p>
 */
@ApplicationScoped
public class CompteIdentityAugmentor implements SecurityIdentityAugmentor {

    /** The attribute carrying the {@link Compte} of a Keycloak identity. */
    public static final String ATTRIBUT_COMPTE = "planning.compte";

    /** Set by Quarkus OIDC on every identity it builds: the tenant that verified the token. */
    static final String ATTRIBUT_TENANT = "tenant-id";

    /** The one role a token of the MCP transport tenant may carry here. */
    static final String ROLE_MCP = "mcp";

    @Inject
    CompteService comptes;

    @Inject
    ConfigSecours secours;

    @Override
    public Uni<SecurityIdentity> augment(SecurityIdentity identity, AuthenticationRequestContext context) {
        if (identity.isAnonymous()) {
            return Uni.createFrom().item(identity);
        }
        if (!(identity.getPrincipal() instanceof JsonWebToken jeton)) {
            return Uni.createFrom().item(formSessionClosed(identity) ? withRoles(identity, Set.of(), null) : identity);
        }
        if (McpTenantResolver.TENANT.equals(identity.getAttribute(ATTRIBUT_TENANT))) {
            return Uni.createFrom()
                    .item(withRoles(identity, identity.hasRole(ROLE_MCP) ? Set.of(ROLE_MCP) : Set.of(), null));
        }
        String email = jeton.getClaim(Claims.email);
        if (email == null || email.isBlank() || !Boolean.TRUE.equals(jeton.getClaim(Claims.email_verified))) {
            return Uni.createFrom().item(withRoles(identity, Set.of(), null));
        }
        return context.runBlocking(() -> {
            Compte compte = comptes.signIn(email, jeton.getClaim(Claims.full_name), jeton.getSubject());
            if (!compte.actif()) {
                return withRoles(identity, Set.of(), compte);
            }
            QuarkusSecurityIdentity.Builder builder = QuarkusSecurityIdentity.builder(identity)
                    .addRoles(CompteService.globalRoles(compte, Instant.now()))
                    .addAttribute(ATTRIBUT_COMPTE, compte);
            return builder.build();
        });
    }

    /**
     * The embedded account is the only identity built by form auth: a
     * principal that is not a token and carries {@code admin} came through
     * {@code /j_security_check} — the MCP key and the anonymous identity carry
     * no {@code admin}.
     */
    private boolean formSessionClosed(SecurityIdentity identity) {
        return !secours.enabled() && identity.hasRole("admin");
    }

    /** The same principal, credentials and attributes — with exactly {@code roles}. */
    private static SecurityIdentity withRoles(SecurityIdentity identity, Set<String> roles, Compte compte) {
        QuarkusSecurityIdentity.Builder builder = QuarkusSecurityIdentity.builder()
                .setPrincipal(identity.getPrincipal())
                .addCredentials(identity.getCredentials())
                .addAttributes(identity.getAttributes())
                .addRoles(roles)
                .setAnonymous(false);
        if (compte != null) {
            builder.addAttribute(ATTRIBUT_COMPTE, compte);
        }
        return builder.build();
    }
}
