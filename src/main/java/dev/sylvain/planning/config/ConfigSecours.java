package dev.sylvain.planning.config;

import io.smallrye.config.ConfigMapping;
import java.util.Optional;

/**
 * The break-glass door (ADR 0093): the single embedded {@code admin} account
 * and its form login.
 *
 * <p>Without an OIDC provider it is the only door, so it is open. Behind one it
 * is the weakest door — one shared password, no second factor — and it is
 * <b>closed by default</b>: {@code ADMIN_LOCAL_SECOURS=true} opens it for the
 * day the provider does not answer, and {@code false} closes it explicitly.
 * Opening and every login through it are written to the admin login journal.</p>
 */
@ConfigMapping(prefix = "planning.auth.secours")
public interface ConfigSecours {

    /** What the operator said; absent means "follow {@code OIDC_ENABLED}". */
    Optional<Boolean> enabled();

    /** Whether {@code /j_security_check} may open a session, given whether a provider is plugged in. */
    default boolean open(boolean oidcEnabled) {
        return enabled().orElse(!oidcEnabled);
    }
}
