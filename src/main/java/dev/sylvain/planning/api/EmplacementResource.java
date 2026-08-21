package dev.sylvain.planning.api;

import java.util.List;

import dev.sylvain.planning.domain.Emplacement;
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
 * CRUD des emplacements — les lieux physiques où les stands sont posés.
 */
@Path("/emplacements")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class EmplacementResource {

    @Inject
    ReferenceDataService referenceDataService;

    @GET
    public List<Emplacement> listEmplacements() {
        return referenceDataService.listEmplacements();
    }

    @POST
    public Emplacement createEmplacement(Emplacement emplacement) {
        return referenceDataService.createEmplacement(emplacement);
    }

    @PUT
    @Path("/{id}")
    public Emplacement updateEmplacement(@PathParam("id") String id, Emplacement emplacement) {
        return referenceDataService.updateEmplacement(id, emplacement);
    }

    @DELETE
    @Path("/{id}")
    public Response deleteEmplacement(@PathParam("id") String id) {
        referenceDataService.deleteEmplacement(id);
        return Response.noContent().build();
    }

}
