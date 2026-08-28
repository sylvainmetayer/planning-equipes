package dev.sylvain.planning.api;

import java.util.List;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.service.ConfirmationPlanningService;
import dev.sylvain.planning.service.ReferenceDataService;
import dev.sylvain.planning.service.ReferenceUsage;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * CRUD of the animateurs, plus the rotation of their espace access token and
 * the read of who acknowledged the published planning.
 */
@Path("/animateurs")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AnimateurResource {

    @Inject
    ReferenceDataService referenceDataService;

    @Inject
    ConfirmationPlanningService confirmationService;

    @GET
    public List<Animateur> listAnimateurs() {
        return referenceDataService.listAnimateurs();
    }

    /**
     * Who acknowledged the published planning (issue #293): one line per
     * animateur, NON_VU included, so the screen can show a column rather than
     * a second list to reconcile by hand.
     *
     * <p>A separate read from {@code GET /api/animateurs} on purpose: the
     * roster is cached and reloaded on every CRUD write, while this answer
     * changes on its own — an animateur clicking in their espace moves it with
     * nothing else happening on the admin side.</p>
     */
    @GET
    @Path("/confirmations")
    public List<ConfirmationPlanningService.ConfirmationView> confirmations() {
        return confirmationService.byAnimateur();
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
     * What deleting these animateurs would take with it — one aggregated total
     * for the whole selection, which is what the confirmation dialog shows.
     * Repeat {@code id} to count several at once; a bulk delete asks once,
     * never once per row.
     */
    @GET
    @Path("/usages")
    public ReferenceUsage countAnimateurUsages(@QueryParam("id") List<String> ids) {
        return referenceDataService.countAnimateurUsages(ids);
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
