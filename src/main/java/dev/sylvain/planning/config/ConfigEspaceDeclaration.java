package dev.sylvain.planning.config;

import io.smallrye.config.ConfigMapping;
import java.time.Duration;

/**
 * Rate limit on the availability declarations of the espace animateur: the
 * first route that ever <b>wrote</b> from a public, Internet-facing espace.
 */
@ConfigMapping(prefix = "planning.espace.declaration")
public interface ConfigEspaceDeclaration {

    int maxEnvois();

    Duration fenetre();
}
