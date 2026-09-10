package dev.sylvain.planning.config;

import io.smallrye.config.ConfigMapping;
import java.time.Duration;

/**
 * Rate limit on the espace animateur's access codes: the codes travel by
 * e-mail, so an unbounded endpoint is both a way to guess one and a way to
 * flood someone's inbox.
 */
@ConfigMapping(prefix = "planning.espace.code")
public interface ConfigEspaceCode {

    int maxDemandes();

    Duration fenetre();
}
