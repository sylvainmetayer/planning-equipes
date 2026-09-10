package dev.sylvain.planning.api;

import dev.sylvain.planning.domain.VerrouillagePlanning;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
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
 * CRUD over the parts of the planning frozen by the user (issue #87). Follows
 * the {@code /api/ad-hoc-constraints} shape: list, create, delete — a lock is
 * state, so it is never updated, only removed and recreated.
 */
@Path("/verrouillages")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class VerrouillageResource {

    @Inject
    ReferenceDataService referenceDataService;

    /** Every lock, all groupes de créneaux included, most recent first. */
    @GET
    public List<VerrouillagePlanning> list() {
        return referenceDataService.listVerrouillages();
    }

    /**
     * Records a lock. The groupe de créneaux defaults to the active one, and
     * the id to a generated UUID. Locking an already-locked target succeeds
     * without creating a duplicate.
     */
    @POST
    public Response create(VerrouillagePlanning verrouillage) {
        return Response.ok(referenceDataService.createVerrouillage(verrouillage))
                .build();
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") String id) {
        referenceDataService.deleteVerrouillage(id);
        return Response.noContent().build();
    }
}
