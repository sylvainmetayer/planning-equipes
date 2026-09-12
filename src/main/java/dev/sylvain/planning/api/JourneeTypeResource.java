package dev.sylvain.planning.api;

import dev.sylvain.planning.domain.JourneeType;
import dev.sylvain.planning.service.referentiel.JourneeTypeService.EtatJourneesTypes;
import dev.sylvain.planning.service.referentiel.JourneeTypeService.RapportApplication;
import dev.sylvain.planning.service.referentiel.JourneesTypesMaterialisation.Affectation;
import dev.sylvain.planning.service.referentiel.JourneesTypesMaterialisation.Reconnaissance;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
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
import java.util.List;

/**
 * Day templates and their calendar (ADR 0032): the templates as a CRUD, the
 * calendar as a whole, and the two operations that connect them to the grid —
 * applying the calendar (previewed first, like every bulk write of créneaux)
 * and recognising the templates a grid already implies.
 */
@Path("/journees-types")
@Produces(MediaType.APPLICATION_JSON)
public class JourneeTypeResource {

    @Inject
    ReferenceDataService referenceDataService;

    /** Templates, calendar, and the dates whose créneaux no longer match their template. */
    @GET
    public EtatJourneesTypes etat() {
        return referenceDataService.etatJourneesTypes();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public JourneeType create(JourneeType journeeType) {
        return referenceDataService.createJourneeType(journeeType);
    }

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    public JourneeType update(@PathParam("id") long id, JourneeType journeeType) {
        return referenceDataService.updateJourneeType(id, journeeType);
    }

    /** The template goes and its dates are no longer governed; the créneaux it produced stay. */
    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") long id) {
        referenceDataService.deleteJourneeType(id);
        return Response.noContent().build();
    }

    /** The calendar as a whole: a date left out is no longer governed by any template. */
    @PUT
    @Path("/calendrier")
    @Consumes(MediaType.APPLICATION_JSON)
    public EtatJourneesTypes setCalendrier(List<Affectation> calendrier) {
        return referenceDataService.setCalendrierJourneesTypes(calendrier);
    }

    /** What applying the calendar would change, créneau by créneau, and the verdict on the result — nothing written. */
    @POST
    @Path("/application/apercu")
    public RapportApplication previewApplication() {
        return referenceDataService.previewJourneesTypes();
    }

    /**
     * Materialises the calendar: kept créneaux keep their id and seats, missing
     * ones are created, unnamed ones on governed dates are removed with their
     * seats. The grid is then declared as vacations.
     */
    @POST
    @Path("/application")
    public RapportApplication apply() {
        return referenceDataService.applyJourneesTypes();
    }

    /** The templates the current grid implies — nothing written. */
    @POST
    @Path("/reconnaissance/apercu")
    public Reconnaissance previewReconnaissance() {
        return referenceDataService.previewReconnaissanceJourneesTypes();
    }

    /** Replaces every template and the whole calendar by what the grid implies. */
    @POST
    @Path("/reconnaissance")
    public Reconnaissance reconnaitre() {
        return referenceDataService.reconnaitreJourneesTypes();
    }
}
