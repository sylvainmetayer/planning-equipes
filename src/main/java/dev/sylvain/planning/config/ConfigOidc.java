package dev.sylvain.planning.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Sign-in through an OpenID Connect provider (see {@code site/src/docs/installer/oidc.md}, ADR
 * 0093): any provider, a client's ADFS or a Keycloak or Zitadel in between.
 *
 * <p>Off by default: the embedded account ({@link ConfigSecours}) is then the
 * door, as it always was. On, the provider says <b>who</b> is calling — a
 * stable subject and an address — and this application decides what that
 * person may do: the {@code compte} and {@code habilitation} tables
 * ({@code service.compte.CompteService}). An unknown or deactivated account is
 * refused, whatever roles the provider might put in its tokens.</p>
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
     * Bind an account to a session only if the {@code email_verified} claim is
     * {@code true}. Right for a provider where anyone can register an address;
     * an ADFS never sends the claim, its addresses being the directory's own,
     * and is plugged in with {@code false}.
     */
    @WithDefault("true")
    boolean requireEmailVerified();
}
