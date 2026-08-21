package dev.sylvain.planning.api;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

import dev.sylvain.planning.domain.PlanningFestival;
import dev.sylvain.planning.scenario.ScenarioValidator;
import dev.sylvain.planning.scenario.dto.EditionCibleDto;
import dev.sylvain.planning.service.EditionContext;
import dev.sylvain.planning.service.EditionService;
import dev.sylvain.planning.service.ImpactImport;
import dev.sylvain.planning.service.PlanningService;
import dev.sylvain.planning.service.ReferenceDataService;
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
    EditionContext editionContext;

    @POST
    @Path("/import")
    public Response importReferenceData(PlanningFestival planning) {
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
    public ImpactImport impactImport() {
        return referenceDataService.compterImpactImport();
    }

    /**
     * What the confirmation dialog must say about WHERE a scenario import
     * would write, before anything is imported: the {@code edition:} section
     * of the file (or named scenario), resolved against the existing
     * editions. {@code editionId} null = no section, the import would write
     * to the caller's current edition.
     */
    public record CibleImportView(String editionId, String editionNomFichier, boolean existe,
            String editionNomExistant) {
    }

    private CibleImportView versCibleView(Optional<EditionCibleDto> cible) {
        if (cible.isEmpty()) {
            return new CibleImportView(null, null, false, null);
        }
        EditionCibleDto dto = cible.get();
        return editionService.listEditions().stream()
                .filter(edition -> edition.getId().equals(dto.id().trim()))
                .findFirst()
                .map(edition -> new CibleImportView(edition.getId(), dto.nom(), true, edition.getNom()))
                .orElseGet(() -> new CibleImportView(dto.id().trim(), dto.nom(), false, null));
    }

    @GET
    @Path("/cible-scenario")
    public CibleImportView cibleScenario(@QueryParam("name") String name) {
        return versCibleView(planningService.chargerSectionsScenario(name).edition());
    }

    @POST
    @Path("/cible-scenario-fichier")
    @Consumes(MediaType.WILDCARD)
    public Response cibleScenarioFichier(String yamlContent) {
        return Response.ok(versCibleView(planningService.chargerEditionTexteScenario(yamlContent))).build();
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
        return importer(planningService.chargerScenario(name));
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
    public Response importScenarioFichier(String yamlContent) {
        return importer(planningService.construireDepuisTexteScenario(yamlContent));
    }

    /**
     * The import itself, identical whether the scenario came bundled or was
     * uploaded — the two endpoints now differ only in where the bytes were
     * read from. It used to be written out twice, and the two copies had
     * drifted: the bundled path re-read the file once per optional section.
     */
    private Response importer(PlanningService.ScenarioImporte importe) {
        PlanningService.SectionsScenario sections = importe.sections();
        return importerDansCible(sections.edition(), () -> {
            sections.parametresLegaux().ifPresent(referenceDataService::updateParametresLegaux);
            sections.parametresDecoupage().ifPresent(referenceDataService::updateParametresDecoupage);
            sections.parametresSolveur().ifPresent(referenceDataService::updateParametresSolveur);
            if (sections.decoupageAuto()) {
                referenceDataService.appliquerDecoupageAutomatique(importe.planning());
                appliquerTypologies(sections);
                return true;
            }
            referenceDataService.importFromPlanning(importe.planning());
            appliquerTypologies(sections);
            return false;
        });
    }

    /**
     * Runs {@code importAction} against the edition the scenario's optional
     * {@code edition:} section designates — created empty when missing, reused
     * otherwise — or plainly against the caller's current edition when the
     * file names none. The response always reports where the data landed (and
     * whether the edition was just created), because the operator's browser
     * may be sitting on a different edition than the one that was written:
     * the UI shows that recap unconditionally.
     */
    private Response importerDansCible(Optional<EditionCibleDto> cibleDto, Callable<Boolean> importAction) {
        try {
            if (cibleDto.isEmpty()) {
                return Response.ok(new ImportScenarioResult(importAction.call(), null, null, null)).build();
            }
            EditionService.CibleImport cible =
                    editionService.resoudrePourImport(cibleDto.get().id(), cibleDto.get().nom());
            boolean decoupageAuto = editionContext.executeDans(cible.edition().getId(), importAction);
            return Response.ok(new ImportScenarioResult(decoupageAuto,
                    cible.edition().getId(), cible.edition().getNom(), cible.creee())).build();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Import de scénario échoué", e);
        }
    }

    /**
     * Applies the scenario's optional {@code typologies:} section, if any,
     * <b>after</b> the planning itself has been imported: {@code
     * ImportReferentielRepository#importFromPlanning} auto-derives an id-as-its-
     * own-label typologie entry for every id a stand/animateur references and
     * unconditionally overwrites any existing label when it does — so an
     * explicit {@code {id, label}} pair from the scenario must be applied
     * afterwards to actually stick, not before.
     */
    private void appliquerTypologies(PlanningService.SectionsScenario sections) {
        sections.typologies().forEach(referenceDataService::createTypologie);
    }

    /**
     * Body returned by {@link #importScenario} and {@link #importScenarioFichier}
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
     * matching a declared stand) that {@link #importScenarioFichier} performs
     * on a real import.
     */
    @POST
    @Path("/valider-scenario-fichier")
    @Consumes(MediaType.WILDCARD)
    public ScenarioValidationResult validerScenarioFichier(String yamlContent) {
        List<String> erreurs = validerScenario(yamlContent);
        return new ScenarioValidationResult(erreurs.isEmpty(), erreurs);
    }

    private static List<String> validerScenario(String yamlContent) {
        if (yamlContent == null || yamlContent.isBlank()) {
            return List.of("Le fichier est vide.");
        }
        try {
            return ScenarioValidator.valider(yamlContent);
        } catch (IOException e) {
            String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return List.of("YAML invalide : " + message);
        }
    }

    public record ScenarioValidationResult(boolean valide, List<String> erreurs) {
    }
}
