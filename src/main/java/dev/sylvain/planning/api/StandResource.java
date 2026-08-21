package dev.sylvain.planning.api;

import java.util.List;

import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.CompactageHoraires;
import dev.sylvain.planning.service.ReferenceDataService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
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
 * CRUD des stands, plus le compactage de leurs horaires.
 */
@Path("/stands")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class StandResource {

    @Inject
    ReferenceDataService referenceDataService;

    @GET
    public List<Stand> listStands() {
        return referenceDataService.listStands();
    }

    @POST
    public Response createStand(Stand stand) {
        return Response.ok(referenceDataService.createStand(stand)).build();
    }

    @PUT
    @Path("/{id}")
    public Response updateStand(@PathParam("id") String id, Stand stand) {
        return Response.ok(referenceDataService.updateStand(id, stand)).build();
    }

    @DELETE
    @Path("/{id}")
    public Response deleteStand(@PathParam("id") String id) {
        referenceDataService.deleteStand(id);
        return Response.noContent().build();
    }

    /**
     * Rewrites hand-entered dated windows as the recurring horaires they repeat
     * — the way a dataset captured before rules existed catches up with them.
     *
     * <p>{@code appliquer} defaults to {@code false}: the call is then a dry run
     * that returns exactly what it <em>would</em> do, per stand, so the report
     * can be shown before anything is written. Only {@code appliquer=true}
     * persists.</p>
     */
    @POST
    @Path("/compactage-horaires")
    public CompactageHoraires.RapportCompactage compacterHoraires(
            @QueryParam("appliquer") @DefaultValue("false") boolean appliquer) {
        return referenceDataService.compacterHoraires(appliquer);
    }

}
