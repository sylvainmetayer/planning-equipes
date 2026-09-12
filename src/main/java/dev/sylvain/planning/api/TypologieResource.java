package dev.sylvain.planning.api;

import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.referentiel.ReferentielCsvImportReport;
import dev.sylvain.planning.service.referentiel.ReferentielCsvImportRequest;
import dev.sylvain.planning.service.referentiel.ReferentielCsvImportService;
import dev.sylvain.planning.service.referentiel.TypologieItem;
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
 * CRUD of the typologie reference data — the game categories stands and
 * animateurs point at.
 */
@Path("/typologies")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class TypologieResource {

    @Inject
    ReferenceDataService referenceDataService;

    private static final ReferentielCsvImportReport.ImportTarget CIBLE =
            ReferentielCsvImportReport.ImportTarget.TYPOLOGIES;

    @GET
    public List<TypologieItem> listTypologies() {
        return referenceDataService.listTypologies();
    }

    @POST
    public TypologieItem createTypologie(TypologieItem typologie) {
        return referenceDataService.createTypologie(typologie);
    }

    @PUT
    @Path("/{id}")
    public TypologieItem updateTypologie(@PathParam("id") String id, TypologieItem typologie) {
        return referenceDataService.updateTypologie(id, typologie);
    }

    /** Returns 400 (rather than a raw FK-violation 500) when the typologie is still assigned to a stand/animateur. */
    @DELETE
    @Path("/{id}")
    public Response deleteTypologie(@PathParam("id") String id) {
        referenceDataService.deleteTypologie(id);
        return Response.noContent().build();
    }

    /* ------------------------------ Import CSV ------------------------------ */

    /** The shape the import expects, shown rather than described. */
    @GET
    @Path("/import-csv/exemple")
    @Produces("text/csv")
    public Response exempleCsv() {
        return CsvDownload.attachment(
                referenceDataService.exempleCsvReferentiel(CIBLE), ReferentielCsvImportService.exampleFileName(CIBLE));
    }

    /** What the file would do, line by line, without writing any of it. */
    @POST
    @Path("/import-csv/analyse")
    @Consumes(MediaType.APPLICATION_JSON)
    public ReferentielCsvImportReport analyseCsv(ReferentielCsvImportRequest request) {
        return referenceDataService.previewCsvReferentiel(CIBLE, request);
    }

    /** Applies the same file the preview was computed from; the server reads it again before writing. */
    @POST
    @Path("/import-csv")
    @Consumes(MediaType.APPLICATION_JSON)
    public ReferentielCsvImportReport importCsv(ReferentielCsvImportRequest request) {
        return referenceDataService.importCsvReferentiel(CIBLE, request);
    }
}
