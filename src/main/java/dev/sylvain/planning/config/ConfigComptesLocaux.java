package dev.sylvain.planning.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Local accounts, signed in with a password (ADR 0094). They stay open behind
 * an OIDC provider: only the configuration's break-glass account is closed
 * there ({@link ConfigSecours}).
 */
@ConfigMapping(prefix = "planning.auth.local")
public interface ConfigComptesLocaux {

    /**
     * A six-digit code mailed after the password, on by default: a password
     * alone opens the personal data of ~150 people, minors included. An operator
     * who runs without a mailer, or behind an access proxy that already asks for
     * a second factor, turns it off ({@code AUTH_LOCAL_SECONDE_ETAPE=false}).
     */
    @WithDefault("true")
    boolean secondeEtape();
}
