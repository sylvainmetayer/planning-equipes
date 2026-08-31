package dev.sylvain.planning.api;

import java.util.List;

import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.service.ReferenceDataService;
import dev.sylvain.planning.service.ReferenceUsage;
import dev.sylvain.planning.service.WrittenCreneau;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
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
 * CRUD of the timeslot grid.
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

    /**
     * Creates a timeslot. The body carries the timeslot — the generated id
     * included — <b>and</b> the non-blocking warnings the write raised
     * ({@link WrittenCreneau}).
     */
    @POST
    public WrittenCreneau createCreneau(Creneau creneau) {
        return referenceDataService.writeCreneau(creneau);
    }

    /** Same body, same warnings, for an edit — see {@link #createCreneau}. */
    @PUT
    @Path("/{id}")
    public WrittenCreneau updateCreneau(@PathParam("id") Long id, Creneau creneau) {
        return referenceDataService.writeCreneau(id, creneau);
    }

    @DELETE
    @Path("/{id}")
    public Response deleteCreneau(@PathParam("id") Long id) {
        referenceDataService.deleteCreneau(id);
        return Response.noContent().build();
    }

    /**
     * What deleting these timeslots would take with it — one aggregated total
     * for the whole selection, which is what the confirmation dialog shows.
     * Repeat {@code id} to count several at once; a bulk delete asks once,
     * never once per row.
     *
     * <p>The ids are taken as text although a timeslot id is a number: bound
     * as a {@code List<Long>} they would be converted by the container, whose
     * failure is a {@code 404} raised before this method runs. A mistyped
     * query field is a {@code 400}, and the conversion therefore belongs to
     * {@code ReferenceUsageService}.</p>
     */
    @GET
    @Path("/usages")
    public ReferenceUsage countCreneauUsages(@QueryParam("id") List<String> ids) {
        return referenceDataService.countCreneauUsages(ids);
    }

}
