package dev.sylvain.planning.api;

import dev.sylvain.planning.service.PlanningService;
import dev.sylvain.planning.service.PlanningService.CreneauAvailability;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/**
 * Le « banc de touche » d'un créneau (issue #303): who is off duty then, and
 * why the seat probed is out of their reach — every reason being the name of a
 * constraint the solver actually enforces.
 *
 * <p>Read-only, on the last persisted plan, and no solve is ever started: like
 * {@code /api/staffing} and the per-assignment explanation, it asks the
 * constraints about a plan that already exists rather than producing one to
 * throw away. Assigning someone from here is deliberately out of scope — that
 * is the day-J mode's job (issue #297).</p>
 *
 * <p>Its own path rather than a sub-resource of {@code /api/creneaux}: JAX-RS
 * maps one root path to one resource class, and {@code CreneauResource} is the
 * referential CRUD, which this is not.</p>
 */
@Path("/banc-de-touche")
@Produces(MediaType.APPLICATION_JSON)
public class CreneauAvailabilityResource {

    @Inject
    PlanningService planningService;

    /**
     * @param standId narrows the probed seat to one stand; ignored when
     *                {@code posteId} names a seat outright
     * @param posteId the exact seat to reason about — what the day-J screens
     *                pass when the user points at a specific hole
     */
    @GET
    @Path("/{creneauId}")
    public CreneauAvailability creneauAvailability(@PathParam("creneauId") long creneauId,
            @QueryParam("standId") String standId, @QueryParam("posteId") String posteId) {
        return planningService.persistedCreneauAvailability(creneauId, standId, posteId);
    }
}
