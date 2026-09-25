package dev.sylvain.planning.api;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.service.publication.ConfirmationPlanningService;
import dev.sylvain.planning.service.publication.RelanceManuelleService;
import dev.sylvain.planning.service.referentiel.AnimateurCsvImportReport;
import dev.sylvain.planning.service.referentiel.AnimateurCsvImportRequest;
import dev.sylvain.planning.service.referentiel.AnimateurCsvImportService;
import dev.sylvain.planning.service.referentiel.CompetencesGrilleImportReport;
import dev.sylvain.planning.service.referentiel.CompetencesGrilleImportRequest;
import dev.sylvain.planning.service.referentiel.CompetencesGrilleService;
import dev.sylvain.planning.service.referentiel.GrilleCompetences;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.referentiel.ReferenceUsage;
import dev.sylvain.planning.service.referentiel.WrittenAnimateur;
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
import java.util.List;

/**
 * CRUD of the animateurs, plus the rotation of their espace access token, the
 * read of who acknowledged the published planning — and the hand that reminds
 * the silent ones —, and the tabular import.
 */
@Path("/animateurs")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AnimateurResource {

    private final ReferenceDataService referenceDataService;

    private final ConfirmationPlanningService confirmationService;

    private final RelanceManuelleService relanceService;

    private final AnimateurCsvImportService csvImport;

    private final CompetencesGrilleService competencesGrille;

    @Inject
    public AnimateurResource(
            ReferenceDataService referenceDataService,
            ConfirmationPlanningService confirmationService,
            RelanceManuelleService relanceService,
            AnimateurCsvImportService csvImport,
            CompetencesGrilleService competencesGrille) {
        this.referenceDataService = referenceDataService;
        this.confirmationService = confirmationService;
        this.relanceService = relanceService;
        this.csvImport = csvImport;
        this.competencesGrille = competencesGrille;
    }

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
     * The same answers in three numbers (issue #504) — confirmed, reminded,
     * silent — next to the date of the publication they answer, for the head
     * of the Animateurs page and for whichever screen needs the counts without
     * summing the table itself.
     */
    @GET
    @Path("/confirmations/synthese")
    public ConfirmationPlanningService.SyntheseConfirmations syntheseConfirmations() {
        return confirmationService.synthese();
    }

    /**
     * « Relancer maintenant » (issue #504): reminds the listed animateurs of
     * their unconfirmed planning, by hand and outside the nightly run. Same
     * message as the night, same rule — nobody is reminded twice about the
     * same publication — so the report says who was written to and who was
     * left alone, and why. {@code 400} while nothing was ever published.
     */
    @POST
    @Path("/relances")
    public RelanceManuelleService.RapportRelance relancer(RelanceDemande demande) {
        return relanceService.relancer(demande == null ? null : demande.animateurIds());
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
        return Response.ok(new AnimateurToken(referenceDataService.regenerateAnimateurToken(id)))
                .build();
    }

    /**
     * The example roster the import screen offers for download: the nine
     * columns this import reads, filled with a dozen fictional people — a
     * minor during the event, a manager, competences with their levels — on
     * the typologies and dates of the {@code festival-realiste-canicule} scenario.
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

    /* ---------------------------- Competences grid ---------------------------- */

    /** The grid as submitted: only the animateurs that were edited, each with their whole map of appreciations. */
    public record SaisieGrilleCompetences(List<GrilleCompetences.SaisieCompetences> animateurs) {}

    /** One line per row submitted, in the order submitted — written, stale or refused. */
    public record RapportSaisieGrilleCompetences(List<GrilleCompetences.LigneCompetences> animateurs) {}

    /**
     * Writes the appreciations typed in the grid, one fiche per row with its own
     * precondition (issue #362). Always {@code 200} once the request is
     * admissible: the rows are independent and each line of the body says how
     * its row ended, {@code STALE} being the per-row form of the {@code 409}
     * a fiche save answers. {@code 409} as a whole only while a solve runs.
     */
    @PUT
    @Path("/competences/grille")
    public RapportSaisieGrilleCompetences saveCompetencesGrid(SaisieGrilleCompetences saisie) {
        return new RapportSaisieGrilleCompetences(competencesGrille.saveGrid(
                saisie == null || saisie.animateurs() == null ? List.of() : saisie.animateurs()));
    }

    /**
     * The grid as a CSV — ids of animateurs in rows, ids of typologies in
     * columns, a level or nothing per cell. No prénom, no nom: the id is what
     * the import needs, and a file naming nobody travels lighter.
     */
    @GET
    @Path("/competences/export")
    @Produces("text/csv")
    public Response exportCompetencesGrid() {
        return CsvDownload.attachment(competencesGrille.exportCsv(), CompetencesGrilleService.EXPORT_FICHIER);
    }

    /**
     * What the competences matrix would do, without doing any of it: the
     * typologie each column landed on, one line of report per animateur row.
     * A pure read — no transaction is opened.
     */
    @POST
    @Path("/competences/import-grille/analyse")
    public CompetencesGrilleImportReport analyseCompetencesGrid(CompetencesGrilleImportRequest request) {
        return competencesGrille.preview(request);
    }

    /**
     * Applies the same request the preview was computed from — file included,
     * re-read and re-checked — and writes the accepted fiches in one
     * transaction. {@code 409} while a solve runs.
     */
    @POST
    @Path("/competences/import-grille")
    public CompetencesGrilleImportReport importCompetencesGrid(CompetencesGrilleImportRequest request) {
        return competencesGrille.apply(request);
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
    public record AnimateurToken(String token) {}

    /** Body of a manual reminder: the ids to write to, nothing else — {@code {"animateurIds": […]}}. */
    public record RelanceDemande(List<String> animateurIds) {}
}
