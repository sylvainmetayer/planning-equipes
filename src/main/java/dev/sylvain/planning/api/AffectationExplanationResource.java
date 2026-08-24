package dev.sylvain.planning.api;

import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.service.PlanningService;
import dev.sylvain.planning.service.PlanningService.AffectationExplanation;
import dev.sylvain.planning.service.PlanningService.SwapSimulation;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Per-assignment explainability ("Pourquoi lui ?"): given an already-solved
 * {@link PlanningEvenement} (as posted by the client — never re-solved here),
 * explains why a specific {@code PosteAffectation} is scored the way it is,
 * and lets the caller simulate handing that same poste to a different
 * animateur to see the score impact before actually changing anything.
 */
@Path("/postes")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AffectationExplanationResource {

    @Inject
    PlanningService planningService;

    /**
     * Constraint matches (violated and not) involving the given poste in the
     * posted planning. The planning must already be solved/persisted — this
     * never triggers a solve.
     */
    @POST
    @Path("/{posteId}/explication")
    public Response explain(@PathParam("posteId") String posteId, PlanningEvenement planning) {
        AffectationExplanation explication = planningService.explainAffectation(planning, posteId);
        return Response.ok(explication).build();
    }

    /**
     * Simulates giving {@code posteId} to {@code animateurId} instead of its
     * current occupant, without persisting anything, and reports the
     * resulting score delta.
     */
    @POST
    @Path("/{posteId}/simulation-swap")
    public Response simulateSwap(@PathParam("posteId") String posteId,
            @QueryParam("animateurId") String animateurId, PlanningEvenement planning) {
        SwapSimulation simulation = planningService.simulateSwap(planning, posteId, animateurId);
        return Response.ok(simulation).build();
    }

}
