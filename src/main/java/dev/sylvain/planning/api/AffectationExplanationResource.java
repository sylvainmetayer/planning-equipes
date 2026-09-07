package dev.sylvain.planning.api;

import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.service.DeplacementService;
import dev.sylvain.planning.service.PlanningService;
import dev.sylvain.planning.service.PlanningWhatIf.AffectationExplanation;
import dev.sylvain.planning.service.PlanningWhatIf.SuggestionsReparation;
import dev.sylvain.planning.service.PlanningWhatIf.SwapSimulation;
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
 *
 * <p>Also hosts the repair assistant of issue #71 — the search for viable
 * candidates, and the one write that applies the chosen one to the persisted
 * plan. Same {@code /postes} path, so the two live in the same resource
 * rather than in two classes JAX-RS would refuse to map.</p>
 */
@Path("/postes")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AffectationExplanationResource {

    @Inject
    PlanningService planningService;

    @Inject
    DeplacementService deplacementService;

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

    /**
     * Repair suggestions for one poste (issue #71): the assistant that
     * <em>looks for</em> candidates, where {@code simulation-swap} only scores
     * the one it is given. Nothing is persisted, and the cost is bounded —
     * {@code plafond} caps how many candidates are simulated, and the answer
     * reports both how many were eligible and how many were actually evaluated.
     */
    @POST
    @Path("/{posteId}/suggestions-reparation")
    public Response suggererReparations(@PathParam("posteId") String posteId,
            @QueryParam("plafond") Integer plafond, PlanningEvenement planning) {
        SuggestionsReparation suggestions = planningService.suggererReparations(planning, posteId, plafond);
        return Response.ok(suggestions).build();
    }

    /**
     * Applies one suggestion to the persisted plan: that seat changes hands and
     * nothing else does. The only endpoint here that writes — hence a separate,
     * explicit call rather than a flag on the simulation.
     *
     * Scores a seat movement (issue #308) without writing: the seat's animateur
     * dropped on another seat ({@code cible}) or on a person
     * ({@code animateur}).
     *
     * <p>Always scored on the persisted plan, prepared server-side. It takes no
     * body: the verdict must be read on the rules the edition actually runs
     * under — the constraints the operator switched off included — and those
     * are not the client's to send.</p>
     */
    @POST
    @Path("/{posteId}/deplacement/simulation")
    @Consumes(MediaType.WILDCARD)
    public Response simulateDeplacement(@PathParam("posteId") String posteId,
            @QueryParam("cible") String posteCibleId, @QueryParam("animateur") String animateurCibleId) {
        return Response.ok(deplacementService.simulate(posteId, posteCibleId, animateurCibleId)).build();
    }

    /**
     * Applies a seat movement to the persisted plan: simulated server-side
     * first and refused (400) when it would worsen the hard score, so a client
     * that skipped the simulation is held to the same rule. Answers what was
     * done, scores before and after included.
     *
     * @param occupant who the caller believes holds the seat. The gesture names
     *                 a seat, and a day view left open shows a plan somebody
     *                 else may have moved since, so without this the server
     *                 moves whoever sits there now — the wrong person, with a
     *                 200. Mismatch is a 409; omitted, no precondition.
     */
    @POST
    @Path("/{posteId}/deplacement")
    @Consumes(MediaType.WILDCARD)
    public Response applyDeplacement(@PathParam("posteId") String posteId,
            @QueryParam("cible") String posteCibleId, @QueryParam("animateur") String animateurCibleId,
            @QueryParam("occupant") String occupant) {
        return Response.ok(deplacementService.apply(posteId, posteCibleId, animateurCibleId, occupant)).build();
    }

    /**
     * Applies one suggestion to the persisted plan: that seat changes hands and
     * nothing else does. The only endpoint here that writes by name — hence a
     * separate, explicit call rather than a flag on the simulation.
     *
     * @param animateurId omitted empties the seat
     */
    @POST
    @Path("/{posteId}/affectation")
    @Consumes(MediaType.WILDCARD)
    public Response applyReparation(@PathParam("posteId") String posteId,
            @QueryParam("animateurId") String animateurId) {
        planningService.applyReparation(posteId, animateurId);
        return Response.noContent().build();
    }

}
