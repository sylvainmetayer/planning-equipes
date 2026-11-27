package dev.sylvain.planning.api;

import dev.sylvain.planning.domain.PlageStand;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.referentiel.CoherenceAnalyzer;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.referentiel.ReferenceUsage;
import dev.sylvain.planning.service.referentiel.WrittenStand;
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
import java.time.LocalDate;
import java.util.List;

/**
 * CRUD of the stands.
 */
@Path("/stands")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class StandResource {

    private final ReferenceDataService referenceDataService;

    @Inject
    public StandResource(ReferenceDataService referenceDataService) {
        this.referenceDataService = referenceDataService;
    }

    @GET
    public List<Stand> listStands() {
        return referenceDataService.listStands();
    }

    /** The stands without an emplacement, and where their neighbours say they belong (a suggestion, never written). */
    @GET
    @Path("/suggestions-emplacement")
    public CoherenceAnalyzer.EmplacementsManquants suggestEmplacements() {
        return referenceDataService.findMissingEmplacements();
    }

    /**
     * Writes the stand and answers it back, together with the non-blocking
     * warnings its schedule raised ({@link WrittenStand}) — a window that
     * overlaps no créneau, an exception dated outside the event, a stand that
     * ends up open nowhere. Written all the same: see {@code Avertissement}.
     */
    @POST
    public WrittenStand createStand(Stand stand) {
        return referenceDataService.writeStand(stand);
    }

    @PUT
    @Path("/{id}")
    public WrittenStand updateStand(@PathParam("id") String id, Stand stand) {
        return referenceDataService.writeStand(id, stand);
    }

    /**
     * Lays one dated plage on the stand (an opening or a closure, kept as typed by hand) and answers the
     * stand back with its warnings, like {@link #updateStand}. {@code 409} while a solve runs or the
     * stands are frozen.
     */
    @POST
    @Path("/{id}/plages")
    public WrittenStand addPlage(@PathParam("id") String id, PlageStand plage) {
        return referenceDataService.addStandPlage(id, plage);
    }

    /** Removes one plage of the stand, by its id. */
    @DELETE
    @Path("/{id}/plages/{plageId}")
    public WrittenStand removePlage(@PathParam("id") String id, @PathParam("plageId") Long plageId) {
        return referenceDataService.removeStandPlages(id, plageId, null);
    }

    /** Removes every plage of the stand on {@code date}: the stand is open all day again that day. */
    @DELETE
    @Path("/{id}/plages")
    public WrittenStand removePlagesOfDay(@PathParam("id") String id, @QueryParam("date") LocalDate date) {
        return referenceDataService.removeStandPlages(id, null, date);
    }

    @DELETE
    @Path("/{id}")
    public Response deleteStand(@PathParam("id") String id) {
        referenceDataService.deleteStand(id);
        return Response.noContent().build();
    }

    /**
     * What deleting these stands would take with it — one aggregated total for
     * the whole selection, which is what the confirmation dialog shows. Repeat
     * {@code id} to count several at once; a bulk delete asks once, never once
     * per row.
     */
    @GET
    @Path("/usages")
    public ReferenceUsage countStandUsages(@QueryParam("id") List<String> ids) {
        return referenceDataService.countStandUsages(ids);
    }
}
