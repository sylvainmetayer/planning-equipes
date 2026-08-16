package dev.sylvain.planning.api;

import java.util.List;

import dev.sylvain.planning.domain.Groupe;
import dev.sylvain.planning.service.GroupeService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * The editions the whole reference model is partitioned into. Which one a
 * request reads and writes is <b>not</b> decided here: the client designates it
 * per request through the {@code X-Groupe-Id} header (see
 * {@code GroupeHeaderFilter}), so two browser tabs can sit on two different
 * editions at once. These endpoints only manage the list itself.
 */
@Path("/groupes")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class GroupeResource {

    @Inject
    GroupeService groupeService;

    @GET
    public List<Groupe> list() {
        return groupeService.listGroupes();
    }

    /**
     * The group this very request was resolved to. Not redundant with the list:
     * a client whose stored {@code X-Groupe-Id} names a group someone else has
     * deleted silently falls back to the default one, and this is how it finds
     * out which group it is actually looking at.
     */
    @GET
    @Path("/courant")
    public Groupe courant() {
        return groupeService.groupeCourant();
    }

    @POST
    public Response create(Groupe groupe) {
        try {
            return Response.ok(groupeService.creer(groupe)).build();
        } catch (IllegalArgumentException e) {
            return badRequest(e);
        }
    }

    @PUT
    @Path("/{id}")
    public Response rename(@PathParam("id") String id, Groupe groupe) {
        try {
            return Response.ok(groupeService.renommer(id, groupe)).build();
        } catch (IllegalArgumentException e) {
            return badRequest(e);
        }
    }

    /**
     * Copies {@code id}'s whole reference model into a brand-new group —
     * "2026 = 2025 minus the assignments". Solver results are excluded: they
     * belong to the edition they were computed for. This is the action that
     * makes multi-group usable at all; without it, preparing next year's
     * edition means re-importing everything by hand.
     */
    @POST
    @Path("/{id}/dupliquer")
    public Response dupliquer(@PathParam("id") String id, Groupe cible) {
        try {
            return Response.ok(groupeService.dupliquer(id, cible)).build();
        } catch (IllegalArgumentException e) {
            return badRequest(e);
        }
    }

    /** Designates the fallback group for any caller sending no {@code X-Groupe-Id}. */
    @PUT
    @Path("/{id}/defaut")
    public Response definirParDefaut(@PathParam("id") String id) {
        groupeService.definirParDefaut(id);
        return Response.noContent().build();
    }

    /**
     * Drops the group and its whole reference model. Returns 400 with an
     * explanation when it is the default group, the current one, or the last
     * remaining one, rather than letting the caller lose data or end up with
     * nothing to fall back on.
     */
    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") String id) {
        try {
            groupeService.supprimer(id);
            return Response.noContent().build();
        } catch (IllegalArgumentException e) {
            return badRequest(e);
        }
    }

    private static Response badRequest(IllegalArgumentException e) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(new ReferenceDataResource.ErreurValidation(e.getMessage()))
                .build();
    }
}
