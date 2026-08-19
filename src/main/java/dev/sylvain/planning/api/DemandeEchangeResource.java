package dev.sylvain.planning.api;

import java.util.List;

import dev.sylvain.planning.domain.DemandeEchange;
import dev.sylvain.planning.service.DemandeEchangeService;
import dev.sylvain.planning.service.EspaceAnimateurService;
import dev.sylvain.planning.service.EspaceAnimateurService.DemandeEchangeView;
import dev.sylvain.planning.service.PlanningService.EchangeSimulation;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Admin side of the foire au planning (issue #165): review the demandes
 * d'échange, measure their impact against the current persisted planning, and
 * decide. Accepting applies the swap exactly as simulated and pins it
 * (ANIMATEUR_CRENEAU locks); refusing changes nothing. No demande is ever
 * applied without one of these explicit calls.
 */
@Path("/echanges")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DemandeEchangeResource {

    @Inject
    DemandeEchangeService demandeEchangeService;

    @Inject
    EspaceAnimateurService espaceAnimateurService;

    /** Every demande of the current edition, most recent first, all statuts. */
    @GET
    public List<DemandeEchangeView> list() {
        return espaceAnimateurService.versVues(demandeEchangeService.lister());
    }

    /**
     * Fresh impact of one demande against the current persisted planning:
     * score delta and the hard constraints the échange would newly break.
     * Recomputed on demand — the stored prevalidation only reflects the
     * planning at submission time.
     */
    @GET
    @Path("/{id}/impact")
    public Response impact(@PathParam("id") String id) {
        try {
            EchangeSimulation simulation = demandeEchangeService.impact(id);
            return Response.ok(simulation).build();
        } catch (IllegalArgumentException e) {
            return notFound(e);
        }
    }

    @POST
    @Path("/{id}/acceptation")
    public Response accepter(@PathParam("id") String id, Decision decision) {
        try {
            return Response.ok(vue(demandeEchangeService.accepter(id,
                    decision == null ? null : decision.commentaire()))).build();
        } catch (IllegalArgumentException e) {
            return badRequest(e);
        }
    }

    @POST
    @Path("/{id}/refus")
    public Response refuser(@PathParam("id") String id, Decision decision) {
        try {
            return Response.ok(vue(demandeEchangeService.refuser(id,
                    decision == null ? null : decision.commentaire()))).build();
        } catch (IllegalArgumentException e) {
            return badRequest(e);
        }
    }

    private DemandeEchangeView vue(DemandeEchange demande) {
        return espaceAnimateurService.versVues(List.of(demande)).get(0);
    }

    private static Response notFound(IllegalArgumentException e) {
        return Response.status(Response.Status.NOT_FOUND)
                .entity(new ReferenceDataResource.ErreurValidation(e.getMessage()))
                .build();
    }

    private static Response badRequest(IllegalArgumentException e) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(new ReferenceDataResource.ErreurValidation(e.getMessage()))
                .build();
    }

    /** Optional admin comment carried by a decision (the reason of a refusal, typically). */
    public record Decision(String commentaire) {
    }
}
