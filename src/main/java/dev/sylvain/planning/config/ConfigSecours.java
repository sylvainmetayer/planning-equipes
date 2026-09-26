package dev.sylvain.planning.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * The break-glass door (ADR 0054): the single embedded {@code admin} account
 * and its form login, kept for the day Keycloak does not answer.
 *
 * <p>Closed by default in production. Open, it is the weakest door of the
 * application — one shared password, no second factor, no name in the action
 * log beyond {@code admin} — so it is meant to be opened for an incident and
 * closed again, and the application says so in its log at every start.</p>
 */
@ConfigMapping(prefix = "planning.auth.secours")
public interface ConfigSecours {

    @WithDefault("false")
    boolean enabled();
}
