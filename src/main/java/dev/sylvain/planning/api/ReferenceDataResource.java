package dev.sylvain.planning.api;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;
import java.util.Optional;

import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.scenario.ScenarioFormatException;
import dev.sylvain.planning.scenario.ScenarioValidator;
import dev.sylvain.planning.scenario.dto.EditionCibleDto;
import dev.sylvain.planning.service.edition.EditionService;
import dev.sylvain.planning.service.ImportImpact;
import dev.sylvain.planning.service.PlanningService;
import dev.sylvain.planning.service.ReferenceDataService;
import dev.sylvain.planning.service.scenario.ScenarioImportService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

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

    @Inject
    ReferenceDataService referenceDataService;

    @Inject
    PlanningService planningService;

    @Inject
    EditionService editionService;

    @Inject
    ScenarioImportService scenarioImportService;

    @POST
    @Path("/import")
    public Response importReferenceData(PlanningEvenement planning) {
        referenceDataService.importFromPlanning(planning);
        return Response.noContent().build();
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
     * editions. {@code editionId} null = no section, the import would write
     * to the caller's current edition.
     */
    @Schema(requiredProperties = {"existe"})
    public record ImportTargetView(String editionId, String editionNomFichier, boolean existe,
            String editionNomExistant) {
    }

    private ImportTargetView toTargetView(Optional<EditionCibleDto> target) {
        if (target.isEmpty()) {
            return new ImportTargetView(null, null, false, null);
        }
        EditionCibleDto dto = target.get();
        return editionService.listEditions().stream()
                .filter(edition -> edition.getId().equals(dto.id().trim()))
                .findFirst()
                .map(edition -> new ImportTargetView(edition.getId(), dto.nom(), true, edition.getNom()))
                .orElseGet(() -> new ImportTargetView(dto.id().trim(), dto.nom(), false, null));
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
        return Response.ok(toTargetView(planningService.loadEditionScenarioText(yamlContent))).build();
    }

    /**
     * Loads a scenario by name entirely server-side and imports its reference
     * data into the database. The scenario file is parsed on the backend, so
     * the (potentially large) planning never travels to the browser and back —
     * the client only sends the desired scenario name.
     *
     * <p>A scenario may optionally pin {@code parametresLegaux:},
     * {@code parametresDecoupage:} and/or {@code parametresSolveur:} — when
     * present, they are persisted too, so the parameters a scenario was
     * authored/verified against travel with it instead of silently depending
     * on whatever is already configured. {@code parametresSolveur} in
     * particular lets a large scenario auto-configure the termination
     * duration it actually needs (Données tab), instead of leaving the
     * caller to guess or under-time a solve. Absent, the current database
     * values are left untouched.
     *
     * <p>A scenario may also pin {@code decoupageAuto:}, applied after
     * {@code parametresDecoupage:} so the découpage it triggers already runs
     * against the parameters the scenario itself pinned — sparing the operator
     * the manual "Découpage" screen round-trip after every import of that
     * scenario. When it does, this returns 200 with an
     * {@link ImportScenarioResult} carrying the target edition's name instead
     * of the usual 204, so the frontend can notify the operator.
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
     * on the Scénarios page, for a file produced by "Exporter les données
     * actuelles en scénario" (or hand-authored in the same shape). Returns
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
        return Response.ok(new ImportScenarioResult(outcome.decoupageAuto(), outcome.editionId(),
                outcome.editionNom(), outcome.editionCreee())).build();
    }

    /**
     * Body returned by {@link #importScenario} and {@link #importScenarioFile}
     * when the scenario carried a {@code decoupageAuto:} section, so the
     * frontend can notify the operator that the imported amplitudes were
     * auto-sliced into the vacations the edition now holds.
     */
    public record ImportScenarioResult(boolean decoupageAuto, String editionId, String editionNom,
            Boolean editionCreee) {
    }

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
    public record ScenarioValidationResult(boolean valide, List<String> erreurs) {
    }
}
