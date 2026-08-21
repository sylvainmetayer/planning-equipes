package dev.sylvain.planning.api;

import dev.sylvain.planning.service.EnvoiPlanningService;
import dev.sylvain.planning.service.EnvoiPlanningService.CompteRenduEnvoi;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Les deux boutons d'envoi des plannings individuels (suite de l'issue #165).
 *
 * <p>Rien d'autre ici que le transport : {@link EnvoiPlanningService} décide
 * qui est concerné, construit les PDF et rend le compte rendu ; les erreurs
 * métier portent leur propre statut HTTP (voir {@code ErreurMetier}), donc
 * aucun {@code try/catch} n'a de raison d'être à cet étage.</p>
 */
@Path("/planning/envoi")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class EnvoiPlanningResource {

    @Inject
    EnvoiPlanningService envoiPlanningService;

    /** Sends their planning to every animateur holding at least one poste. */
    @POST
    @Path("/tous")
    public CompteRenduEnvoi envoyerATous() {
        return envoiPlanningService.envoyerATous();
    }

    /**
     * Sends one animateur their planning; 400 without an address, 404 unknown,
     * 500 when the send itself failed — that last one carries the service's
     * message, because the operator's next move is to read it.
     */
    @POST
    @Path("/animateur/{animateurId}")
    public Response envoyerAUnAnimateur(@PathParam("animateurId") String animateurId) {
        CompteRenduEnvoi compteRendu = envoiPlanningService.envoyerAUnAnimateur(animateurId);
        return compteRendu.echecs().isEmpty()
                ? Response.ok(compteRendu).build()
                : Response.serverError().entity(new ErreurValidation(compteRendu.echecs().get(0))).build();
    }
}
