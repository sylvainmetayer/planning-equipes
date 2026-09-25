package dev.sylvain.planning.api;

import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.referentiel.WrittenContrainteAdHoc;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;

/**
 * The constraints entered by hand (affinities, incompatibilities, …).
 */
@Path("/contraintes-ad-hoc")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ContrainteAdHocResource {

    private final ReferenceDataService referenceDataService;

    @Inject
    public ContrainteAdHocResource(ReferenceDataService referenceDataService) {
        this.referenceDataService = referenceDataService;
    }

    @GET
    public List<ContrainteAdHoc> listContraintesAdHoc() {
        return referenceDataService.listContraintesAdHoc();
    }

    /**
     * Create-or-overwrite by id. Answers {@code { contrainte, avertissements }}
     * like the other referentials that warn: a forced assignment on its
     * animateurs' days off is written, and said.
     */
    @POST
    public WrittenContrainteAdHoc createContrainteAdHoc(ContrainteAdHoc contrainteAdHoc) {
        return referenceDataService.writeContrainteAdHoc(contrainteAdHoc);
    }

    @DELETE
    @Path("/{id}")
    public Response deleteContrainteAdHoc(@PathParam("id") String id) {
        referenceDataService.deleteContrainteAdHoc(id);
        return Response.noContent().build();
    }
}
