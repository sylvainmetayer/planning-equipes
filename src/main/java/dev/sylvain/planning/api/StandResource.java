package dev.sylvain.planning.api;

import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.referentiel.HoraireCompaction;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.referentiel.ReferenceUsage;
import dev.sylvain.planning.service.referentiel.ReferentielCsvImportReport;
import dev.sylvain.planning.service.referentiel.ReferentielCsvImportRequest;
import dev.sylvain.planning.service.referentiel.ReferentielCsvImportService;
import dev.sylvain.planning.service.referentiel.StandGrilleImportReport;
import dev.sylvain.planning.service.referentiel.StandGrilleImportRequest;
import dev.sylvain.planning.service.referentiel.StandGrilleImportService;
import dev.sylvain.planning.service.referentiel.WrittenStand;
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
import java.util.List;

/**
 * CRUD of the stands, plus the compaction of their opening hours.
 */
@Path("/stands")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class StandResource {

    private final ReferenceDataService referenceDataService;

    private static final ReferentielCsvImportReport.ImportTarget CIBLE = ReferentielCsvImportReport.ImportTarget.STANDS;

    private final StandGrilleImportService grilleImport;

    @Inject
    public StandResource(ReferenceDataService referenceDataService, StandGrilleImportService grilleImport) {
        this.referenceDataService = referenceDataService;
        this.grilleImport = grilleImport;
    }

    @GET
    public List<Stand> listStands() {
        return referenceDataService.listStands();
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

    /**
     * Rewrites hand-entered dated windows as the recurring horaires they repeat
     * — the way a dataset captured before rules existed catches up with them.
     *
     * <p>{@code apply} defaults to {@code false}: the call is then a dry run
     * that returns exactly what it <em>would</em> do, per stand, so the report
     * can be shown before anything is written. Only {@code apply=true}
     * persists.</p>
     */
    @POST
    @Path("/compactage-horaires")
    public HoraireCompaction.RapportCompactage compactHoraires(
            @QueryParam("appliquer") @DefaultValue("false") boolean apply) {
        return referenceDataService.compactHoraires(apply);
    }

    /* ----------------------------- Matrix import ----------------------------- */

    /**
     * What the stand matrix would do, without doing any of it: the columns
     * read and the créneau each landed on, one line of report per stand row.
     * A pure read — no transaction is opened.
     */
    @POST
    @Path("/import-grille/analyse")
    public StandGrilleImportReport analyseGrille(StandGrilleImportRequest request) {
        return grilleImport.preview(request);
    }

    /**
     * Applies the same request the preview was computed from — file included,
     * re-read and re-checked — and writes the accepted stands in one
     * transaction. {@code 409} while a solve runs.
     */
    @POST
    @Path("/import-grille")
    public StandGrilleImportReport importGrille(StandGrilleImportRequest request) {
        return grilleImport.apply(request);
    }

    /** The edition's own matrix as a CSV to start from: its créneaux as columns, its stands as rows. */
    @GET
    @Path("/import-grille/exemple")
    @Produces("text/csv")
    public Response exempleGrille() {
        return CsvDownload.attachment(grilleImport.exemple(), StandGrilleImportService.EXEMPLE_FICHIER);
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
