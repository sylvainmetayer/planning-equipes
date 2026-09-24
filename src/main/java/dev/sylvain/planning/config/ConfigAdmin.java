package dev.sylvain.planning.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithName;

/**
 * What the admin interface offers on this instance, as opposed to what it
 * shows: switches an operator sets per deployment, served to the frontend
 * through {@code /api/config}.
 *
 * <p>No {@code @WithDefault}: the default is the one in
 * {@code application.properties}, next to the environment variable that
 * overrides it, so there is a single place where it is written.</p>
 */
@ConfigMapping(prefix = "planning.admin")
public interface ConfigAdmin {

    /**
     * The day views let a seat be dragged onto another line
     * ({@code GLISSER_DEPOSER_ACTIF}). Off by default while the gesture is not
     * reliable enough; only the gesture is switched off, the move itself stays
     * reachable through the API and the MCP tools.
     */
    @WithName("glisser-deposer")
    boolean dragDropEnabled();
}
