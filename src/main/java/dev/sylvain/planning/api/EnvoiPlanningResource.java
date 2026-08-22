package dev.sylvain.planning.api;

import dev.sylvain.planning.service.PlanningDeliveryService;
import dev.sylvain.planning.service.PlanningDeliveryService.DeliveryReport;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * The two buttons that send the individual plannings (follow-up to issue
 * #165).
 *
 * <p>Nothing but transport here: {@link PlanningDeliveryService} decides who is
 * concerned, builds the PDFs and returns the report; a business error carries
 * its own HTTP status (see {@code BusinessError}), so no {@code try/catch} has
 * any reason to exist at this level.</p>
 */
@Path("/planning/envoi")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class EnvoiPlanningResource {

    @Inject
    PlanningDeliveryService envoiPlanningService;

    /** Sends their planning to every animateur holding at least one poste. */
    @POST
    @Path("/tous")
    public DeliveryReport sendToAll() {
        return envoiPlanningService.sendToAll();
    }

    /**
     * Sends one animateur their planning; 400 without an address, 404 unknown,
     * 500 when the send itself failed — that last one carries the service's
     * message, because the operator's next move is to read it.
     */
    @POST
    @Path("/animateur/{animateurId}")
    public Response sendToOneAnimateur(@PathParam("animateurId") String animateurId) {
        DeliveryReport compteRendu = envoiPlanningService.sendToOneAnimateur(animateurId);
        return compteRendu.echecs().isEmpty()
                ? Response.ok(compteRendu).build()
                : Response.serverError().entity(new ValidationError(compteRendu.echecs().get(0))).build();
    }
}
