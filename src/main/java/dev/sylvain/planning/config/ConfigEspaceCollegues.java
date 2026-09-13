package dev.sylvain.planning.config;

import io.smallrye.config.ConfigMapping;
import java.time.Duration;

/**
 * Rate limit on a colleague's seats in the espace animateur: how many
 * <b>distinct</b> colleagues one animateur may look up per window.
 */
@ConfigMapping(prefix = "planning.espace.collegues")
public interface ConfigEspaceCollegues {

    int maxCollegues();

    Duration fenetre();
}
