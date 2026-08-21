package dev.sylvain.planning.config;

import java.util.Optional;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * The opt-in mode where an access proxy asserts an already-authenticated
 * address instead of the application running its own login (issue #165).
 *
 * <p>A header is a claim, not a proof: the mode refuses to boot without
 * {@link #secret()}, because anything that can reach the application port
 * could otherwise assert any identity it likes.</p>
 */
@ConfigMapping(prefix = "planning.auth.remote-user")
public interface ConfigRemoteUser {

    @WithDefault("false")
    boolean enabled();

    /** Header carrying the address the proxy vouches for. */
    @WithDefault("Remote-Email")
    String header();

    /** Header carrying the shared secret that makes the claim trustworthy. */
    @WithDefault("Remote-Auth-Secret")
    String secretHeader();

    Optional<String> secret();

    /** The one address that gets the admin role; everyone else is an animateur. */
    Optional<String> adminEmail();
}
