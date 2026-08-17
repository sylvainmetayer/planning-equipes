package dev.sylvain.planning.api;

import java.util.List;

import dev.sylvain.planning.domain.Edition;
import dev.sylvain.planning.service.EditionService;
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
 * per request through the {@code X-Edition-Id} header (see
 * {@code EditionHeaderFilter}), so two browser tabs can sit on two different
 * editions at once. These endpoints only manage the list itself.
 */
@Path("/editions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class EditionResource {

    @Inject
    EditionService editionService;

    @GET
    public List<Edition> list() {
        return editionService.listEditions();
    }

    /**
     * The edition this very request was resolved to. Not redundant with the
     * list: a client whose stored {@code X-Edition-Id} names an edition someone
     * else has deleted silently falls back to the default one, and this is how
     * it finds out which edition it is actually looking at.
     */
    @GET
    @Path("/courant")
    public Edition courant() {
        return editionService.editionCourante();
    }

    @POST
    public Response create(Edition edition) {
        try {
            return Response.ok(editionService.creer(edition)).build();
        } catch (IllegalArgumentException e) {
            return badRequest(e);
        }
    }

    @PUT
    @Path("/{id}")
    public Response rename(@PathParam("id") String id, Edition edition) {
        try {
            return Response.ok(editionService.renommer(id, edition)).build();
        } catch (IllegalArgumentException e) {
            return badRequest(e);
        }
    }

    /**
     * Copies {@code id}'s whole reference model into a brand-new edition —
     * "2026 = 2025 minus the assignments". Solver results are excluded: they
     * belong to the edition they were computed for. This is the action that
     * makes multi-edition usable at all; without it, preparing next year's
     * edition means re-importing everything by hand.
     */
    @POST
    @Path("/{id}/dupliquer")
    public Response dupliquer(@PathParam("id") String id, Edition cible) {
        try {
            return Response.ok(editionService.dupliquer(id, cible)).build();
        } catch (IllegalArgumentException e) {
            return badRequest(e);
        }
    }

    /** Designates the fallback edition for any caller sending no {@code X-Edition-Id}. */
    @PUT
    @Path("/{id}/defaut")
    public Response definirParDefaut(@PathParam("id") String id) {
        editionService.definirParDefaut(id);
        return Response.noContent().build();
    }

    /**
     * Drops the edition and its whole reference model. Returns 400 with an
     * explanation when it is the default edition, the current one, or the last
     * remaining one, rather than letting the caller lose data or end up with
     * nothing to fall back on.
     */
    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") String id) {
        try {
            editionService.supprimer(id);
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
