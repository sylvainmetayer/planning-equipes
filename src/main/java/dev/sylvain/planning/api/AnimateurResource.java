package dev.sylvain.planning.api;

import java.util.List;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.service.AnimateurCsvImportReport;
import dev.sylvain.planning.service.AnimateurCsvImportRequest;
import dev.sylvain.planning.service.AnimateurCsvImportService;
import dev.sylvain.planning.service.publication.ConfirmationPlanningService;
import dev.sylvain.planning.service.ReferenceDataService;
import dev.sylvain.planning.service.ReferenceUsage;
import dev.sylvain.planning.service.WrittenAnimateur;
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
 * CRUD of the animateurs, plus the rotation of their espace access token, the
 * read of who acknowledged the published planning, and the tabular import.
 */
@Path("/animateurs")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AnimateurResource {

    @Inject
    ReferenceDataService referenceDataService;

    @Inject
    ConfirmationPlanningService confirmationService;

    @Inject
    AnimateurCsvImportService csvImport;

    @GET
    public List<Animateur> listAnimateurs() {
        return referenceDataService.listAnimateurs();
    }

    /**
     * Who acknowledged the published planning (issue #293): one line per
     * animateur, NON_VU included, so the screen can show a column rather than
     * a second list to reconcile by hand.
     *
     * <p>A separate read from {@code GET /api/animateurs} on purpose: the
     * roster is cached and reloaded on every CRUD write, while this answer
     * changes on its own — an animateur clicking in their espace moves it with
     * nothing else happening on the admin side.</p>
     */
    @GET
    @Path("/confirmations")
    public List<ConfirmationPlanningService.ConfirmationView> confirmations() {
        return confirmationService.byAnimateur();
    }

    /**
     * Creates an animateur. The body carries the fiche <b>and</b> the
     * non-blocking warnings the write raised ({@link WrittenAnimateur}) — the
     * fiche is written either way, so this stays a {@code 200}.
     *
     * <p>Declared as {@link WrittenAnimateur} rather than wrapped in a
     * {@code Response}: the declared return type is what {@code JsonContractTest}
     * walks, and a payload nothing declares is a payload nothing freezes.</p>
     */
    @POST
    public WrittenAnimateur createAnimateur(Animateur animateur) {
        return referenceDataService.writeAnimateur(animateur);
    }

    /** Same body, same warnings, for an edit — see {@link #createAnimateur}. */
    @PUT
    @Path("/{id}")
    public WrittenAnimateur updateAnimateur(@PathParam("id") String id, Animateur animateur) {
        return referenceDataService.writeAnimateur(id, animateur);
    }

    @DELETE
    @Path("/{id}")
    public Response deleteAnimateur(@PathParam("id") String id) {
        referenceDataService.deleteAnimateur(id);
        return Response.noContent().build();
    }

    /**
     * What deleting these animateurs would take with it — one aggregated total
     * for the whole selection, which is what the confirmation dialog shows.
     * Repeat {@code id} to count several at once; a bulk delete asks once,
     * never once per row.
     */
    @GET
    @Path("/usages")
    public ReferenceUsage countAnimateurUsages(@QueryParam("id") List<String> ids) {
        return referenceDataService.countAnimateurUsages(ids);
    }

    /**
     * Rotates the animateur's espace access token (issue #165): the link
     * printed on an already-distributed PDF stops working, the fiche shows the
     * new one. Regeneration is the only way a token ever changes.
     */
    @POST
    @Path("/{id}/token")
    public Response regenerateAnimateurToken(@PathParam("id") String id) {
        return Response.ok(new AnimateurToken(referenceDataService.regenerateAnimateurToken(id))).build();
    }

    /**
     * The example roster the import screen offers for download: the nine
     * columns this import reads, filled with the anonymised animateurs of the
     * {@code festival-realiste} scenario.
     *
     * <p>Served from the classpath rather than copied into the front-end
     * bundle, so there is exactly one file to keep true — and a test re-imports
     * that same file through the real parser.</p>
     */
    @GET
    @Path("/import-csv/exemple")
    @Produces("text/csv")
    public Response exempleCsvAnimateurs() {
        return CsvDownload.attachment(csvImport.exemple(), AnimateurCsvImportService.EXEMPLE_FICHIER);
    }

    /**
     * What a CSV file would do, without doing any of it: the columns read, the
     * mapping used, and one line of report per row of the file.
     *
     * <p>Separate from {@link #importCsvAnimateurs} on purpose, and not just
     * as a convenience: an import that previews and writes in the same call
     * has no moment at which the operator can say no. This one is a pure read
     * — no transaction is opened at all.</p>
     */
    @POST
    @Path("/import-csv/analyse")
    public AnimateurCsvImportReport analyseCsvAnimateurs(AnimateurCsvImportRequest request) {
        return csvImport.preview(request);
    }

    /**
     * Applies the same request the preview was computed from — file included.
     *
     * <p>The body is the file, not the preview: the server re-reads it and
     * re-runs every check before writing, so a replayed or hand-crafted call
     * cannot get a row past a validation. The answer is the report again, this
     * time with {@code applied: true} and counts that a committed transaction
     * stands behind.</p>
     */
    @POST
    @Path("/import-csv")
    public AnimateurCsvImportReport importCsvAnimateurs(AnimateurCsvImportRequest request) {
        return csvImport.apply(request);
    }

    /**
     * Body of a token regeneration: the new token, nothing else —
     * {@code {"token": "…"}}.
     *
     * <p>The component name <b>is</b> the JSON key, which is why it is frozen
     * by {@code JsonContractTest}. It read {@code jeton} until the chain was
     * aligned end to end (column, key and path). The frontend does not read
     * this body at all — it reloads the fiche after rotating — so the only
     * reader to keep in mind is a direct API caller.</p>
     */
    public record AnimateurToken(String token) {
    }

}
