package dev.sylvain.planning.api;

import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.service.solve.DeplacementService;
import dev.sylvain.planning.service.solve.PlanningService;
import dev.sylvain.planning.service.solve.PlanningWhatIf.AffectationExplanation;
import dev.sylvain.planning.service.solve.PlanningWhatIf.DeplacementSimulation;
import dev.sylvain.planning.service.solve.PlanningWhatIf.SuggestionsReparation;
import dev.sylvain.planning.service.solve.PlanningWhatIf.SwapSimulation;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * The two gestures of the Siège panel on one seat of the persisted plan
 * (ADR 0099 for who may call which): « Expliquer » — why the holder is scored
 * the way they are — and « Simuler un changement » — the repair suggestions
 * and what a change would do, before and after, both {@code GET}s that write
 * nothing — plus the writes that apply a change: placement, move, hand-over.
 *
 * <p>The repair search also answers at
 * {@code POST /api/jour-j/postes/{posteId}/suggestions}, the Aujourd'hui
 * screen's. Same {@code /postes} path for the rest, so they live in the same
 * resource rather than in two classes JAX-RS would refuse to map.</p>
 */
@Path("/postes")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AffectationExplanationResource {

    private final PlanningService planningService;

    private final DeplacementService deplacementService;

    @Inject
    public AffectationExplanationResource(PlanningService planningService, DeplacementService deplacementService) {
        this.planningService = planningService;
        this.deplacementService = deplacementService;
    }

    /**
     * Constraint matches (violated and not) involving the given poste in the
     * <b>persisted</b> plan, prepared server-side under this edition's rules —
     * the constraints switched off, the weights, the quotas. What the Siège
     * panel's « Pourquoi lui ? » reads. Takes no body, never triggers a solve;
     * 409 while nothing has been solved yet.
     */
    @GET
    @Path("/{posteId}/explication")
    public AffectationExplanation explainPersisted(@PathParam("posteId") String posteId) {
        return planningService.persistedExplainAffectation(posteId);
    }

    /**
     * « Simuler un changement » of the Siège panel: what a change of this seat
     * would do — its holder moved to another seat ({@code cible}) or the seat
     * handed to a person ({@code animateur}) — scored on the persisted plan
     * prepared server-side, before and after. Writes nothing, hence a
     * {@code GET}: a lecteur simulates too (ADR 0099), and only the write
     * that applies it is refused to them.
     */
    @GET
    @Path("/{posteId}/simulation")
    public DeplacementSimulation simulate(
            @PathParam("posteId") String posteId,
            @QueryParam("cible") String posteCibleId,
            @QueryParam("animateur") String animateurCibleId) {
        return deplacementService.simulate(posteId, posteCibleId, animateurCibleId);
    }

    /**
     * The repair suggestions of « Simuler un changement »: the viable
     * candidates for this seat, best impact first, on the persisted plan — the
     * search of {@code POST /api/jour-j/postes/{posteId}/suggestions}, read
     * with a {@code GET} so a lecteur reads them too. {@code depuis} asks for
     * the next batch of the ranked candidates.
     */
    @GET
    @Path("/{posteId}/suggestions")
    public SuggestionsReparation suggestions(
            @PathParam("posteId") String posteId,
            @QueryParam("plafond") Integer plafond,
            @QueryParam("depuis") Integer depuis) {
        return planningService.persistedSuggererReparations(posteId, plafond, depuis);
    }

    /**
     * Simulates giving {@code posteId} to {@code animateurId} instead of its
     * current occupant, without persisting anything, and reports the
     * resulting score delta — on the plan the caller posts. Deprecated until
     * 5.0: no screen calls it any more, and a posted plan carries none of the
     * edition's rules; {@code GET /{posteId}/simulation?animateur=} scores the
     * same change on the persisted plan.
     */
    @POST
    @Deprecated
    @Path("/{posteId}/simulation-swap")
    public Response simulateSwap(
            @PathParam("posteId") String posteId,
            @QueryParam("animateurId") String animateurId,
            PlanningEvenement planning) {
        SwapSimulation simulation = planningService.simulateSwap(planning, posteId, animateurId);
        return Response.ok(simulation).build();
    }

    /**
     * Scores a seat movement (issue #308) without writing: the seat's animateur
     * dropped on another seat ({@code cible}) or on a person
     * ({@code animateur}).
     *
     * <p>Always scored on the persisted plan, prepared server-side. It takes no
     * body: the verdict must be read on the rules the edition actually runs
     * under — the constraints the operator switched off included — and those
     * are not the client's to send.</p>
     *
     * <p>Deprecated until 5.0, an alias of {@code GET /{posteId}/simulation}:
     * a {@code POST} a lecteur cannot call although it writes nothing.</p>
     */
    @POST
    @Deprecated
    @Path("/{posteId}/deplacement/simulation")
    @Consumes(MediaType.WILDCARD)
    public Response simulateDeplacement(
            @PathParam("posteId") String posteId,
            @QueryParam("cible") String posteCibleId,
            @QueryParam("animateur") String animateurCibleId) {
        return Response.ok(simulate(posteId, posteCibleId, animateurCibleId)).build();
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
    public Response applyDeplacement(
            @PathParam("posteId") String posteId,
            @QueryParam("cible") String posteCibleId,
            @QueryParam("animateur") String animateurCibleId,
            @QueryParam("occupant") String occupant) {
        return Response.ok(deplacementService.apply(posteId, posteCibleId, animateurCibleId, occupant))
                .build();
    }

    /**
     * « Placer » (the Siège panel of the Journée): seats {@code animateur} on a
     * seat nobody holds, answered like a move — who went where, the plan's
     * score before and after — so the caller can warn when the medium or soft
     * level drops.
     *
     * <p>The checks of {@code /affectation} apply, a hard rule the seating
     * would break named in the 400, plus two a direct write does not make: the
     * seat must still be free (409 otherwise — the panel shows a plan loaded
     * earlier), and the person must not be under a lock that forbids them a
     * new seat on that timeslot. Keeping the placement at the next solve is a
     * lock the caller posts next, through {@code /verrouillages}: this call
     * seats and nothing else.</p>
     */
    @POST
    @Path("/{posteId}/placement")
    @Consumes(MediaType.WILDCARD)
    public DeplacementSimulation applyPlacement(
            @PathParam("posteId") String posteId, @QueryParam("animateur") String animateurId) {
        return deplacementService.place(posteId, animateurId);
    }

    /**
     * Applies one suggestion to the persisted plan: that seat changes hands and
     * nothing else does. The only endpoint here that writes by name — hence a
     * separate, explicit call rather than a flag on the simulation.
     *
     * <p>The seating is scored before it is written and refused when it would
     * break a hard rule, naming it (ADR 0046). The repair screen never offers
     * such a candidate, so it never meets the refusal; this endpoint takes any
     * animateur its caller names, and used to seat a minor on a night slot
     * without a word. Emptying the seat is never refused.</p>
     *
     * @param animateurId omitted empties the seat
     * @param occupant    who the caller believes holds the seat — the Siège
     *                    panel sends the person it shows before freeing or
     *                    handing over their seat. Held by the write itself: a
     *                    seat somebody else holds by then is a 409 and nothing
     *                    is written. Omitted, no precondition.
     */
    @POST
    @Path("/{posteId}/affectation")
    @Consumes(MediaType.WILDCARD)
    public Response applyReparation(
            @PathParam("posteId") String posteId,
            @QueryParam("animateurId") String animateurId,
            @QueryParam("occupant") String occupant) {
        planningService.applyReparation(posteId, animateurId, occupant);
        return Response.noContent().build();
    }
}
