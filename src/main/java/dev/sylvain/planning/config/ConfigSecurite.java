package dev.sylvain.planning.config;

import io.smallrye.config.ConfigMapping;
import java.util.Optional;

/**
 * The two browser security headers whose value depends on the deployment.
 * Both are {@link Optional} because setting them empty is the documented way
 * to switch them off — a plain {@code String} injection point would refuse to
 * boot instead.
 */
@ConfigMapping(prefix = "planning.securite")
public interface ConfigSecurite {

    Optional<String> csp();

    Optional<String> hsts();
}
