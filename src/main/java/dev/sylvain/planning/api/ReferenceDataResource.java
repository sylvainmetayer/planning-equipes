package dev.sylvain.planning.api;

import dev.sylvain.planning.scenario.ScenarioFormatException;
import dev.sylvain.planning.scenario.ScenarioValidator;
import dev.sylvain.planning.scenario.dto.EditionCibleDto;
import dev.sylvain.planning.service.edition.EditionService;
import dev.sylvain.planning.service.referentiel.ImportImpact;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.referentiel.ReferentielCsvExportService;
import dev.sylvain.planning.service.scenario.ScenarioImportService;
import dev.sylvain.planning.service.solve.PlanningService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Import a whole set of reference data — from a bundled scenario, from an
 * uploaded file, or from a raw planning — and say up front what that would
 * touch.
 *
 * <p>The per-family CRUD each live in their own resource
 * ({@link StandResource}, {@link AnimateurResource}, …). What is left here is
 * what crosses every family at once, and what has to resolve the target
 * edition before writing anything at all.</p>
 */
@Path("/reference-data")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ReferenceDataResource {

    private final ReferenceDataService referenceDataService;

    private final PlanningService planningService;

    private final EditionService editionService;

    private final ScenarioImportService scenarioImportService;

    @Inject
    public ReferenceDataResource(
            ReferenceDataService referenceDataService,
            PlanningService planningService,
            EditionService editionService,
            ScenarioImportService scenarioImportService) {
        this.referenceDataService = referenceDataService;
        this.planningService = planningService;
        this.editionService = editionService;
        this.scenarioImportService = scenarioImportService;
    }

    /**
     * What a scenario import would touch, for the confirmation dialog shown
     * before either import button runs: replaced referentials, the resolved
     * planning about to be erased, the demandes and locks going with it.
     */
    @GET
    @Path("/impact-import")
    public ImportImpact impactImport() {
        return referenceDataService.countImportImpact();
    }

    /**
     * What the confirmation dialog must say about WHERE a scenario import
     * would write, before anything is imported: the {@code edition:} section
     * of the file (or named scenario), resolved against the existing
     * editions. {@code editionId} and {@code editionNomFichier} both null = no
     * section, the import would write to the caller's current edition; an
     * edition to create has no id yet, only its name.
     */
    @Schema(requiredProperties = {"existe"})
    public record ImportTargetView(
            String editionId, String editionNomFichier, boolean existe, String editionNomExistant) {}

    private ImportTargetView toTargetView(Optional<EditionCibleDto> target) {
        if (target.isEmpty()) {
            return new ImportTargetView(null, null, false, null);
        }
        EditionCibleDto dto = target.get();
        String idFichier =
                dto.id() == null || dto.id().isBlank() ? null : dto.id().trim();
        return editionService
                .findForImport(dto.id(), dto.nom())
                .map(edition -> new ImportTargetView(edition.getId(), dto.nom(), true, edition.getNom()))
                .orElseGet(() -> new ImportTargetView(idFichier, dto.nom(), false, null));
    }

    @GET
    @Path("/cible-scenario")
    public ImportTargetView scenarioTarget(@QueryParam("name") String name) {
        return toTargetView(planningService.loadScenarioSections(name).edition());
    }

    @POST
    @Path("/cible-scenario-fichier")
    @Consumes(MediaType.WILDCARD)
    public Response fileScenarioTarget(String yamlContent) {
        return Response.ok(toTargetView(planningService.loadEditionScenarioText(yamlContent)))
                .build();
    }

    /**
     * Loads a scenario by name entirely server-side and imports its reference
     * data into the database. The scenario file is parsed on the backend, so
     * the (potentially large) planning never travels to the browser and back —
     * the client only sends the desired scenario name.
     *
     * <p>A scenario may optionally pin {@code parametresLegaux:} and/or
     * {@code parametresSolveur:} — when
     * present, they are persisted too, so the parameters a scenario was
     * authored/verified against travel with it instead of silently depending
     * on whatever is already configured. {@code parametresSolveur} in
     * particular lets a large scenario auto-configure the termination
     * duration it actually needs (Données tab), instead of leaving the
     * caller to guess or under-time a solve. Absent, the current database
     * values are left untouched.
     *
     * <p>Returns 200 with an {@link ImportScenarioResult} carrying the target
     * edition's name, so the frontend can tell the operator where the data
     * landed — the browser may be sitting on another edition entirely.
     */
    @POST
    @Path("/import-scenario")
    @Consumes(MediaType.WILDCARD)
    public Response importScenario(@QueryParam("name") String name) {
        return toResponse(scenarioImportService.importBundled(name));
    }

    /**
     * Same import as {@link #importScenario}, but for a scenario YAML file
     * uploaded from the user's own machine rather than one bundled under
     * {@code src/main/resources/scenarios} — the "Importer un fichier" button
     * of the Imports screen's Scénario tab, for a file produced by the Exports
     * screen (or hand-authored in the same shape). Returns
     * 400 with the parsing/validation error as-is when the file is invalid,
     * instead of importing nothing silently.
     */
    @POST
    @Path("/import-scenario-fichier")
    @Consumes(MediaType.WILDCARD)
    public Response importScenarioFile(String yamlContent) {
        return toResponse(scenarioImportService.importYaml(yamlContent));
    }

    /**
     * The only thing left of the import in this class: turning where the data
     * landed into a body. The order the sections are applied in, and the
     * resolution of the target edition, belong to
     * {@link ScenarioImportService} — see its javadoc for why the order is the
     * rule.
     */
    private static Response toResponse(ScenarioImportService.ScenarioImportOutcome outcome) {
        return Response.ok(new ImportScenarioResult(outcome.editionId(), outcome.editionNom(), outcome.editionCreee()))
                .build();
    }

    /**
     * Body returned by {@link #importScenario} and {@link #importScenarioFile}:
     * where the data landed, and whether the import had to create that edition.
     */
    public record ImportScenarioResult(String editionId, String editionNom, Boolean editionCreee) {}

    /**
     * Validates a scenario YAML file's structure (types, required fields,
     * value ranges — see docs/schema/scenario-schema.json) without importing
     * anything: the "Validateur YAML" tool page. Always 200 — a malformed or
     * structurally invalid file surfaces as entries in {@code erreurs}
     * rather than an HTTP error, since this is a diagnostic report, not a
     * mutation. Delegates to the standalone {@link ScenarioValidator}, so it
     * only checks the same shape the JSON Schema describes: it does not
     * replicate the cross-reference checks (e.g. a poste's standId actually
     * matching a declared stand) that {@link #importScenarioFile} performs
     * on a real import.
     */
    @POST
    @Path("/valider-scenario-fichier")
    @Consumes(MediaType.WILDCARD)
    public ScenarioValidationResult validateScenarioFile(String yamlContent) {
        List<String> erreurs = validateScenario(yamlContent);
        return new ScenarioValidationResult(erreurs.isEmpty(), erreurs);
    }

    private static List<String> validateScenario(String yamlContent) {
        if (yamlContent == null || yamlContent.isBlank()) {
            return List.of("Le fichier est vide.");
        }
        try {
            return ScenarioValidator.validate(yamlContent);
        } catch (ScenarioFormatException e) {
            // Reported as-is: the binder writes its message for the person who
            // wrote the file, and prefixing it again would only bury it.
            return List.of(e.getMessage());
        }
    }

    @Schema(requiredProperties = {"valide"})
    public record ScenarioValidationResult(boolean valide, List<String> erreurs) {}

    /* ---------------------------- Export CSV ---------------------------- */

    /** How many rows each referential would write, so the screen can say what it offers. */
    @GET
    @Path("/export-csv/volumes")
    public Map<String, Integer> volumesExportCsv() {
        Map<String, Integer> volumes = new LinkedHashMap<>();
        referenceDataService.volumesExportCsv().forEach((cible, total) -> volumes.put(cible.name(), total));
        return volumes;
    }

    /**
     * The chosen referentials as a zip of CSV files, each in the shape its
     * import tab reads back.
     *
     * <p>Every referential is opt-in: the archive holds what was asked for and
     * nothing else, and asking for nothing is a {@code 400} rather than an
     * empty download that looks like it worked.</p>
     */
    @GET
    @Path("/export-csv")
    @Produces("application/zip")
    public Response exportCsv(
            @QueryParam("typologies") boolean typologies,
            @QueryParam("emplacements") boolean emplacements,
            @QueryParam("stands") boolean stands,
            @QueryParam("creneaux") boolean creneaux,
            @QueryParam("journeesTypes") boolean journeesTypes,
            @QueryParam("animateurs") boolean animateurs) {
        Set<ReferentielCsvExportService.ExportTarget> cibles = new LinkedHashSet<>();
        if (typologies) {
            cibles.add(ReferentielCsvExportService.ExportTarget.TYPOLOGIES);
        }
        if (emplacements) {
            cibles.add(ReferentielCsvExportService.ExportTarget.EMPLACEMENTS);
        }
        if (stands) {
            cibles.add(ReferentielCsvExportService.ExportTarget.STANDS);
        }
        if (creneaux) {
            cibles.add(ReferentielCsvExportService.ExportTarget.CRENEAUX);
        }
        if (journeesTypes) {
            cibles.add(ReferentielCsvExportService.ExportTarget.JOURNEES_TYPES);
        }
        if (animateurs) {
            cibles.add(ReferentielCsvExportService.ExportTarget.ANIMATEURS);
        }
        return Response.ok(referenceDataService.exportCsvReferentiels(cibles))
                .header("Content-Disposition", "attachment; filename=\"referentiels-csv.zip\"")
                .build();
    }
}
