package dev.sylvain.planning.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.util.List;

/**
 * Sign-in through an OpenID Connect provider (see {@code docs/oidc.md}, ADR
 * 0077): a client's ADFS, or a Keycloak or Zitadel in between when the
 * directory speaks SAML only or several directories meet.
 *
 * <p>Off by default: the embedded account ({@link ConfigSecours}) is then the
 * door, as it always was. On, the provider says <b>who</b> is calling — an
 * address, and depending on {@link #adminMfa()} a second factor — and this
 * application decides what that person may do: the {@code compte} and
 * {@code habilitation} tables ({@code service.compte.CompteService}), the
 * {@code ADMIN} right included. A provider that maps an {@code admin} or
 * {@code mcp} role into its tokens is honoured as well.</p>
 *
 * <p>The animateurs have no account: their espace opens with the link and the
 * code mailed to the fiche. A provider session whose address is the fiche's
 * opens it without the code, nothing more.</p>
 */
@ConfigMapping(prefix = "planning.auth.oidc")
public interface ConfigOidc {

    /**
     * Master switch. Kept in step with {@code quarkus.oidc.tenant-enabled},
     * which {@code application.properties} derives from the same environment
     * variable — {@link OidcConfiguration} refuses to boot on a mismatch.
     */
    @WithDefault("false")
    boolean enabled();

    /**
     * Refuse a token whose {@code email_verified} claim is not {@code true}.
     * Right for a provider where anyone can register an address (Keycloak,
     * Zitadel with self-registration); an ADFS never sends the claim, its
     * addresses being the directory's own, and is plugged in with
     * {@code false}.
     */
    @WithDefault("true")
    boolean requireEmailVerified();

    /**
     * Who checks the administrators' second factor: {@code provider} trusts the
     * provider's own policy (an ADFS access control policy, a Keycloak flow);
     * {@code token} has this application refuse the {@code admin} role to a
     * session whose {@code amr} claim names none of {@link #adminMfaAmr()}.
     */
    @WithDefault("provider")
    AdminMfa adminMfa();

    /** The {@code amr} values that count as a second factor under {@link AdminMfa#TOKEN}. */
    @WithDefault("mfa,otp,hwk,swk,fido,http://schemas.microsoft.com/claims/multipleauthn")
    List<String> adminMfaAmr();

    enum AdminMfa {
        PROVIDER,
        TOKEN
    }
}
