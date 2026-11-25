package dev.sylvain.planning.api;

import dev.sylvain.planning.config.ConfigOidc;
import dev.sylvain.planning.config.ConfigSecours;
import dev.sylvain.planning.mcp.McpApiKeyIdentityProvider;
import dev.sylvain.planning.mcp.McpTenantResolver;
import dev.sylvain.planning.service.compte.Compte;
import dev.sylvain.planning.service.compte.CompteAccesService;
import dev.sylvain.planning.service.compte.CompteService;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.SecurityIdentityAugmentor;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import org.eclipse.microprofile.jwt.Claims;
import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * Joins the identity Quarkus built to the account this application keeps for
 * the person (ADR 0093), on every authenticated request.
 *
 * <ul>
 *   <li><b>A provider session</b> (keyed by the pair issuer and subject) (its principal is a token) is attached to its
 *       {@code compte} by {@link CompteService#identify}: an unknown account
 *       or a deactivated one — the kill switch an organiser pulls without the
 *       directory — gets <b>no role at all</b>, whatever the provider put in its
 *       token. An active one gets the roles its rights grant, and nothing
 *       else: this application, not the directory, decides who administers
 *       it.</li>
 *   <li><b>A form session while the break-glass door is closed</b> loses its
 *       role: {@link FormLoginSecours} stops new logins, and a cookie issued
 *       while the door was open must not outlive the incident.</li>
 *   <li><b>A local session</b> (a password; the cookie names the account's
 *       address): the account is read again, so a deactivated one keeps no
 *       role from one request to the next.</li>
 *   <li>Anonymous and the MCP API key pass through untouched.</li>
 * </ul>
 *
 * <p>The account is read on a worker thread ({@code runBlocking}): the
 * augmentor runs on the event loop, and JDBC must not.</p>
 */
@ApplicationScoped
public class CompteIdentityAugmentor implements SecurityIdentityAugmentor {

    static final String ROLE_ADMIN = "admin";

    static final String ROLE_ORGANISATEUR = "organisateur";

    static final String ROLE_LECTEUR = "lecteur";

    private final CompteService comptes;
    private final CompteAccesService acces;
    private final ConfigSecours secours;
    private final ConfigOidc oidc;

    @Inject
    public CompteIdentityAugmentor(
            CompteService comptes, CompteAccesService acces, ConfigSecours secours, ConfigOidc oidc) {
        this.comptes = comptes;
        this.acces = acces;
        this.secours = secours;
        this.oidc = oidc;
    }

    @Override
    public Uni<SecurityIdentity> augment(SecurityIdentity identity, AuthenticationRequestContext context) {
        if (identity.isAnonymous()) {
            return Uni.createFrom().item(identity);
        }
        if (Boolean.TRUE.equals(identity.getAttribute(Compte.LOCAL_ATTRIBUTE))) {
            // A local session: the cookie names the address, the account decides —
            // read again on every request, so a deactivation cuts the session.
            return context.runBlocking(() -> {
                Compte compte =
                        acces.activeSession(identity.getPrincipal().getName()).orElse(null);
                if (compte == null) {
                    // A stale session (password changed, account deactivated or
                    // reactivated since): it keeps its principal — the form mechanism
                    // cannot renew a cookie without one — but no role, and is marked
                    // stale so that /api/auth/me says « not signed in » and the login
                    // page offers to sign in again instead of « no rights ».
                    return QuarkusSecurityIdentity.builder()
                            .setPrincipal(identity.getPrincipal())
                            .addAttribute(Compte.STALE_ATTRIBUTE, Boolean.TRUE)
                            .build();
                }
                return withRoles(identity, CompteService.roles(compte, Instant.now()), compte);
            });
        }
        if (!(identity.getPrincipal() instanceof JsonWebToken token)) {
            return Uni.createFrom().item(formSessionClosed(identity) ? withRoles(identity, Set.of(), null) : identity);
        }
        return context.runBlocking(() -> {
            String email = token.getClaim(Claims.email);
            boolean trusted =
                    !oidc.requireEmailVerified() || Boolean.TRUE.equals(token.getClaim(Claims.email_verified));
            Compte compte = comptes.identify(token.getIssuer() + "|" + token.getSubject(), email, trusted)
                    .filter(CompteService::isActive)
                    .orElse(null);
            Set<String> roles = compte == null ? Set.of() : new HashSet<>(CompteService.roles(compte, Instant.now()));
            // The MCP transport is opened by its own role (ADR 0097), only to a token the
            // bearer-only tenant validated, for an administrator, an organiser or a lecteur (who
            // meets only the tools that read, ADR 0099): a responsable de stand opens
            // /api/responsable/* and nothing else.
            if ((roles.contains(ROLE_ADMIN) || roles.contains(ROLE_ORGANISATEUR) || roles.contains(ROLE_LECTEUR))
                    && McpTenantResolver.TENANT.equals(identity.getAttribute("tenant-id"))) {
                roles.add(McpApiKeyIdentityProvider.ROLE_MCP);
            }
            return withRoles(identity, roles, compte);
        });
    }

    /**
     * A form session while the door is closed: a principal that is not a token
     * and carries {@code admin}.
     */
    private boolean formSessionClosed(SecurityIdentity identity) {
        return !secours.open(oidc.enabled()) && identity.hasRole(ROLE_ADMIN);
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
            builder.addAttribute(Compte.IDENTITY_ATTRIBUTE, compte);
        }
        return builder.build();
    }
}
