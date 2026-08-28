package dev.sylvain.planning.config;

import java.time.Duration;

import io.smallrye.config.ConfigMapping;

/**
 * Rate limit on the availability declarations of the espace animateur: the
 * first route that ever <b>wrote</b> from a public, Internet-facing espace.
 */
@ConfigMapping(prefix = "planning.espace.declaration")
public interface ConfigEspaceDeclaration {

    int maxEnvois();

    Duration fenetre();
}
