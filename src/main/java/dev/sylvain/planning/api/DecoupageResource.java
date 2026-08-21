package dev.sylvain.planning.api;

import java.util.List;

import dev.sylvain.planning.domain.Creneau;
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
 * Le découpage des amplitudes de l'édition en vacations : l'aperçu, puis la
 * matérialisation.
 */
@Path("/decoupage")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DecoupageResource {

    @Inject
    ReferenceDataService referenceDataService;

    /** Preview of the vacations the edition's current créneaux (read as amplitudes) would generate — nothing is persisted. */
    @GET
    @Path("/preview")
    public List<Creneau> previsualiserDecoupage() {
        return referenceDataService.previsualiserDecoupage();
    }

    /**
     * Materializes the découpage in place: the edition's créneaux — the
     * amplitudes just previewed — are replaced by the generated vacations
     * (issue #172). Re-running with other parameters means re-importing the
     * scenario, or duplicating an "amplitudes" edition first.
     */
    @POST
    @Path("/generer")
    @Consumes(MediaType.WILDCARD)
    public Response genererDecoupage() {
        referenceDataService.genererDecoupage();
        return Response.noContent().build();
    }

}
