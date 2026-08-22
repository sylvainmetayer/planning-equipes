package dev.sylvain.planning.api;

import java.util.List;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.service.ReferenceDataService;
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
 * CRUD of the animateurs, plus the rotation of their espace access token.
 */
@Path("/animateurs")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AnimateurResource {

    @Inject
    ReferenceDataService referenceDataService;

    @GET
    public List<Animateur> listAnimateurs() {
        return referenceDataService.listAnimateurs();
    }

    @POST
    public Response createAnimateur(Animateur animateur) {
        return Response.ok(referenceDataService.createAnimateur(animateur)).build();
    }

    @PUT
    @Path("/{id}")
    public Response updateAnimateur(@PathParam("id") String id, Animateur animateur) {
        return Response.ok(referenceDataService.updateAnimateur(id, animateur)).build();
    }

    @DELETE
    @Path("/{id}")
    public Response deleteAnimateur(@PathParam("id") String id) {
        referenceDataService.deleteAnimateur(id);
        return Response.noContent().build();
    }

    /**
     * Rotates the animateur's espace access token (issue #165): the link
     * printed on an already-distributed PDF stops working, the fiche shows the
     * new one. Regeneration is the only way a token ever changes.
     */
    @POST
    @Path("/{id}/token")
    public Response regenerateAnimateurToken(@PathParam("id") String id) {
        return Response.ok(new AnimateurToken(referenceDataService.regenerateAnimateurToken(id))).build();
    }

    /**
     * Body of a token regeneration: the new token, nothing else —
     * {@code {"token": "…"}}.
     *
     * <p>The component name <b>is</b> the JSON key, which is why it is frozen
     * by {@code JsonContractTest}. It read {@code jeton} until the chain was
     * aligned end to end (column, key and path). The frontend does not read
     * this body at all — it reloads the fiche after rotating — so the only
     * reader to keep in mind is a direct API caller.</p>
     */
    public record AnimateurToken(String token) {
    }

}
