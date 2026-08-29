package dev.sylvain.planning.api;

import java.util.List;

import dev.sylvain.planning.service.AlerteService;
import dev.sylvain.planning.service.AlerteService.AlerteView;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/**
 * What the scheduled jobs left on the organiser's desk (issues #298, #299,
 * #300).
 *
 * <p>The Notifications screen used to be a purely local log: things this
 * browser saw happen, kept in {@code localStorage}. Everything on this route
 * happened at four in the morning with nobody watching, so it cannot come from
 * there — a swap request rotting for a week, or five people the J-1 reminder
 * could not reach, must be readable from any browser, including one opened for
 * the first time.</p>
 *
 * <p>Read-only, and deliberately so: an alert is closed by doing the thing it
 * is about — deciding the échange, filling in the missing address — not by
 * dismissing it.</p>
 */
@Path("/alertes")
@Produces(MediaType.APPLICATION_JSON)
public class AlerteResource {

    @Inject
    AlerteService alerteService;

    /**
     * The edition's alerts, newest first.
     *
     * @param limite how many to bring back <b>per type</b> — one noisy kind of
     *               alert must not push another off the screen; the service
     *               clamps it, so a client asking for a million gets the cap
     *               rather than the database
     */
    @GET
    public List<AlerteView> alertes(@QueryParam("limite") Integer limite) {
        return alerteService.alertes(limite);
    }
}
