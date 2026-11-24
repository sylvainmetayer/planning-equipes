package dev.sylvain.planning.api;

import dev.sylvain.planning.config.ConfigOidc;
import dev.sylvain.planning.config.ConfigSecours;
import dev.sylvain.planning.mcp.McpTenantResolver;
import dev.sylvain.planning.service.compte.Compte;
import dev.sylvain.planning.service.compte.CompteService;
import dev.sylvain.planning.service.espace.RemoteUserAuthentication;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.SecurityIdentityAugmentor;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import org.eclipse.microprofile.jwt.Claims;
import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * Joins the identity Quarkus built to the account this application keeps for
 * the person (ADR 0077), on every authenticated request.
 *
 * <ul>
 *   <li><b>A provider session with an address</b> — verified, unless
 *       {@code planning.auth.oidc.require-email-verified} is off for a
 *       directory such as an ADFS — is attached to its {@code compte}, created
 *       on the first sign-in. A deactivated account keeps its name and loses
 *       every role — the kill switch an organiser pulls without the
 *       directory. An active one gains the roles its edition-wide rights
 *       grant ({@code ADMIN} included), on top of the roles the token maps.
 *       Under {@code planning.auth.oidc.admin-mfa=token}, {@code admin} is then
 *       withheld from a session whose {@code amr} claim names no second
 *       factor.</li>
 *   <li><b>A form session while the break-glass door is closed</b> loses its
 *       role: {@link FormLoginSecours} stops new logins, and a cookie issued
 *       while the door was open must not outlive the incident by eight
 *       hours. The admin the opt-in remote-user mode asserts on the current
 *       request is not a form session and keeps its role.</li>
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

    /** The attribute carrying the {@link Compte} of an OIDC identity. */
    public static final String ATTRIBUT_COMPTE = Compte.IDENTITY_ATTRIBUTE;

    /** Set by Quarkus OIDC on every identity it builds: the tenant that verified the token. */
    static final String ATTRIBUT_TENANT = "tenant-id";

    /** The one role a token of the MCP transport tenant may carry here. */
    static final String ROLE_MCP = "mcp";

    static final String ROLE_ADMIN = "admin";

    @Inject
    CompteService comptes;

    @Inject
    ConfigSecours secours;

    @Inject
    ConfigOidc oidc;

    @Override
    public Uni<SecurityIdentity> augment(SecurityIdentity identity, AuthenticationRequestContext context) {
        if (identity.isAnonymous()) {
            return Uni.createFrom().item(identity);
        }
        if (!(identity.getPrincipal() instanceof JsonWebToken token)) {
            return Uni.createFrom().item(formSessionClosed(identity) ? withRoles(identity, Set.of(), null) : identity);
        }
        if (McpTenantResolver.TENANT.equals(identity.getAttribute(ATTRIBUT_TENANT))) {
            return Uni.createFrom()
                    .item(withRoles(identity, identity.hasRole(ROLE_MCP) ? Set.of(ROLE_MCP) : Set.of(), null));
        }
        String email = token.getClaim(Claims.email);
        if (email == null
                || email.isBlank()
                || (oidc.requireEmailVerified() && !Boolean.TRUE.equals(token.getClaim(Claims.email_verified)))) {
            return Uni.createFrom().item(withRoles(identity, Set.of(), null));
        }
        return context.runBlocking(() -> {
            Compte compte = comptes.signIn(email, token.getClaim(Claims.full_name), token.getSubject());
            if (!compte.actif()) {
                return withRoles(identity, Set.of(), compte);
            }
            Set<String> roles = new HashSet<>(identity.getRoles());
            roles.addAll(CompteService.globalRoles(compte, Instant.now()));
            if (!secondFactorProven(oidc, token.getClaim("amr"))) {
                roles.remove(ROLE_ADMIN);
            }
            return withRoles(identity, roles, compte);
        });
    }

    /**
     * Whether the session proved a second factor, as far as this application
     * is asked to check: always under {@code admin-mfa=provider}, which leaves
     * the question to the provider's own policy; under {@code token}, when the
     * {@code amr} claim names one of {@code admin-mfa-amr}.
     */
    static boolean secondFactorProven(ConfigOidc config, Object amr) {
        if (config.adminMfa() != ConfigOidc.AdminMfa.TOKEN) {
            return true;
        }
        Set<String> methods = new HashSet<>();
        if (amr instanceof Collection<?> values) {
            values.forEach(v -> methods.add(String.valueOf(v).replace("\"", "")));
        } else if (amr != null) {
            methods.add(String.valueOf(amr).replace("\"", ""));
        }
        return config.adminMfaAmr().stream().anyMatch(methods::contains);
    }

    /**
     * A form session while the door is closed: a principal that is not a token
     * and carries {@code admin}, unless the identity comes from the opt-in
     * remote-user mode ({@code RemoteUserIdentityProvider}, which also names its
     * principal {@code admin}): it has no door to close, the proxy's secret
     * being its own credential. The MCP key and the anonymous identity carry
     * no {@code admin}.
     */
    private boolean formSessionClosed(SecurityIdentity identity) {
        if (secours.enabled() || !identity.hasRole(ROLE_ADMIN)) {
            return false;
        }
        return !Boolean.TRUE.equals(identity.getAttribute(RemoteUserAuthentication.IDENTITY_ATTRIBUTE));
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
