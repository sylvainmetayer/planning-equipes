package dev.sylvain.planning.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * The local door (ADR 0077): the single embedded {@code admin} account and its
 * form login.
 *
 * <p>Open by default: without an OIDC provider it is how the application is
 * administered, as it always was. With one, it is the weakest door — one
 * shared password, no second factor, no name in the action log beyond
 * {@code admin} — and the operator closes it once the provider works,
 * reopening it for the day the provider does not answer.</p>
 */
@ConfigMapping(prefix = "planning.auth.secours")
public interface ConfigSecours {

    @WithDefault("true")
    boolean enabled();
}
