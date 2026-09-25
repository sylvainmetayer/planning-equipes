package dev.sylvain.planning.api;

import dev.sylvain.planning.service.solve.PlanningService;
import dev.sylvain.planning.service.solve.PlanningWhatIf.CreneauAvailability;
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
 *
 * <p>A créneau the saved plan holds no seat on answers {@code 200} with a
 * {@code statut} saying so, not {@code 404}: the selector feeding this screen
 * is the referential, which legitimately holds more créneaux than the plan
 * does. Only a créneau that exists nowhere is a {@code 404}.</p>
 */
@Path("/banc-de-touche")
@Produces(MediaType.APPLICATION_JSON)
public class CreneauAvailabilityResource {

    private final PlanningService planningService;

    @Inject
    public CreneauAvailabilityResource(PlanningService planningService) {
        this.planningService = planningService;
    }

    /**
     * @param standId narrows the probed seat to one stand; ignored when
     *                {@code posteId} names a seat outright
     * @param posteId the exact seat to reason about — what the day-J screens
     *                pass when the user points at a specific hole
     */
    @GET
    @Path("/{creneauId}")
    public CreneauAvailability creneauAvailability(
            @PathParam("creneauId") long creneauId,
            @QueryParam("standId") String standId,
            @QueryParam("posteId") String posteId) {
        return planningService.persistedCreneauAvailability(creneauId, standId, posteId);
    }

    /**
     * Same answer, on a créneau the server picks: the first one the saved plan
     * staffs.
     *
     * <p>It exists because the screen cannot name a valid créneau before its
     * first call. Its selector only offers créneaux the plan staffs — which the
     * answer itself carries — so on a cold open there is nothing to ask for
     * yet, and guessing from the référentiel is what used to land the user on a
     * créneau with nothing to show.</p>
     */
    @GET
    public CreneauAvailability premierCreneau(
            @QueryParam("standId") String standId, @QueryParam("posteId") String posteId) {
        return planningService.persistedCreneauAvailability(null, standId, posteId);
    }
}
