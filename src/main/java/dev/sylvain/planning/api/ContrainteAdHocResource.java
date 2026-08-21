package dev.sylvain.planning.api;

import java.util.List;

import dev.sylvain.planning.domain.ContrainteAdHoc;
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
 * The constraints entered by hand (affinities, incompatibilities, …).
 */
@Path("/contraintes-ad-hoc")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ContrainteAdHocResource {

    @Inject
    ReferenceDataService referenceDataService;

    @GET
    public List<ContrainteAdHoc> listContraintesAdHoc() {
        return referenceDataService.listContraintesAdHoc();
    }

    @POST
    public Response createContrainteAdHoc(ContrainteAdHoc contrainteAdHoc) {
        return Response.ok(referenceDataService.createContrainteAdHoc(contrainteAdHoc)).build();
    }

    @DELETE
    @Path("/{id}")
    public Response deleteContrainteAdHoc(@PathParam("id") String id) {
        referenceDataService.deleteContrainteAdHoc(id);
        return Response.noContent().build();
    }

}
