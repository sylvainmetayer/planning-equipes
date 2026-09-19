package dev.sylvain.planning.api;

import dev.sylvain.planning.domain.VerrouillagePlanning;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.referentiel.WrittenVerrouillage;
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
     *
     * <p>Answers {@code { verrouillage, avertissements }} like the other
     * referentials that warn — freezing seats that already break a hard rule is
     * accepted, and said. Typed rather than wrapped in a {@code Response}: the
     * published OpenAPI is the contract, and a {@code Response} describes
     * nothing in it.</p>
     */
    @POST
    public WrittenVerrouillage create(VerrouillagePlanning verrouillage) {
        return referenceDataService.writeVerrouillage(verrouillage);
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") String id) {
        referenceDataService.deleteVerrouillage(id);
        return Response.noContent().build();
    }
}
