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
 * CRUD de la grille de créneaux.
 */
@Path("/creneaux")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CreneauResource {

    @Inject
    ReferenceDataService referenceDataService;

    @GET
    public List<Creneau> listCreneaux() {
        return referenceDataService.listCreneaux();
    }

    @POST
    public Creneau createCreneau(Creneau creneau) {
        return referenceDataService.createCreneau(creneau);
    }

    @PUT
    @Path("/{id}")
    public Creneau updateCreneau(@PathParam("id") Long id, Creneau creneau) {
        return referenceDataService.updateCreneau(id, creneau);
    }

    @DELETE
    @Path("/{id}")
    public Response deleteCreneau(@PathParam("id") Long id) {
        referenceDataService.deleteCreneau(id);
        return Response.noContent().build();
    }

}
