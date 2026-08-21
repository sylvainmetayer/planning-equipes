package dev.sylvain.planning.api;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Emplacement;
import dev.sylvain.planning.domain.ParametresDecoupage;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.ParametresSolveur;
import dev.sylvain.planning.domain.PlanningFestival;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.scenario.ScenarioValidator;
import dev.sylvain.planning.scenario.dto.EditionCibleDto;
import dev.sylvain.planning.service.CompactageHoraires;
import dev.sylvain.planning.service.EditionContext;
import dev.sylvain.planning.service.EditionService;
import dev.sylvain.planning.service.PlanningService;
import dev.sylvain.planning.service.ReferenceDataService;
import dev.sylvain.planning.service.TypologieItem;
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

@Path("/")
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

    @GET
    @Path("/stands")
    public List<Stand> listStands() {
        return referenceDataService.listStands();
    }

    @POST
    @Path("/stands")
    public Response createStand(Stand stand) {
        return Response.ok(referenceDataService.createStand(stand)).build();
    }

    @PUT
    @Path("/stands/{id}")
    public Response updateStand(@PathParam("id") String id, Stand stand) {
        return Response.ok(referenceDataService.updateStand(id, stand)).build();
    }

    @DELETE
    @Path("/stands/{id}")
    public Response deleteStand(@PathParam("id") String id) {
        referenceDataService.deleteStand(id);
        return Response.noContent().build();
    }

    /**
     * Rewrites hand-entered dated windows as the recurring horaires they repeat
     * — the way a dataset captured before rules existed catches up with them.
     *
     * <p>{@code appliquer} defaults to {@code false}: the call is then a dry run
     * that returns exactly what it <em>would</em> do, per stand, so the report
     * can be shown before anything is written. Only {@code appliquer=true}
     * persists.</p>
     */
    @POST
    @Path("/stands/compactage-horaires")
    public CompactageHoraires.RapportCompactage compacterHoraires(
            @QueryParam("appliquer") @DefaultValue("false") boolean appliquer) {
        return referenceDataService.compacterHoraires(appliquer);
    }

    @GET
    @Path("/emplacements")
    public List<Emplacement> listEmplacements() {
        return referenceDataService.listEmplacements();
    }

    @POST
    @Path("/emplacements")
    public Emplacement createEmplacement(Emplacement emplacement) {
        return referenceDataService.createEmplacement(emplacement);
    }

    @PUT
    @Path("/emplacements/{id}")
    public Emplacement updateEmplacement(@PathParam("id") String id, Emplacement emplacement) {
        return referenceDataService.updateEmplacement(id, emplacement);
    }

    @DELETE
    @Path("/emplacements/{id}")
    public Response deleteEmplacement(@PathParam("id") String id) {
        referenceDataService.deleteEmplacement(id);
        return Response.noContent().build();
    }

    @GET
    @Path("/creneaux")
    public List<Creneau> listCreneaux() {
        return referenceDataService.listCreneaux();
    }

    @POST
    @Path("/creneaux")
    public Creneau createCreneau(Creneau creneau) {
        return referenceDataService.createCreneau(creneau);
    }

    @PUT
    @Path("/creneaux/{id}")
    public Creneau updateCreneau(@PathParam("id") Long id, Creneau creneau) {
        return referenceDataService.updateCreneau(id, creneau);
    }

    @DELETE
    @Path("/creneaux/{id}")
    public Response deleteCreneau(@PathParam("id") Long id) {
        referenceDataService.deleteCreneau(id);
        return Response.noContent().build();
    }

    /** Preview of the vacations the edition's current créneaux (read as amplitudes) would generate — nothing is persisted. */
    @GET
    @Path("/decoupage/preview")
    public List<Creneau> previsualiserDecoupage() {
        return referenceDataService.previsualiserDecoupage();
    }

    /**
     * Materializes the découpage in place: the edition's créneaux — the
     * amplitudes just previewed — are replaced by the generated vacations
     * (issue #172). Re-running with other parameters means re-importing the
     * scenario, or duplicating an "amplitudes" edition first.
     */
    @POST
    @Path("/decoupage/generer")
    @Consumes(MediaType.WILDCARD)
    public Response genererDecoupage() {
        referenceDataService.genererDecoupage();
        return Response.noContent().build();
    }

    @GET
    @Path("/animateurs")
    public List<Animateur> listAnimateurs() {
        return referenceDataService.listAnimateurs();
    }

    @POST
    @Path("/animateurs")
    public Response createAnimateur(Animateur animateur) {
        return Response.ok(referenceDataService.createAnimateur(animateur)).build();
    }

    @PUT
    @Path("/animateurs/{id}")
    public Response updateAnimateur(@PathParam("id") String id, Animateur animateur) {
        return Response.ok(referenceDataService.updateAnimateur(id, animateur)).build();
    }

    @DELETE
    @Path("/animateurs/{id}")
    public Response deleteAnimateur(@PathParam("id") String id) {
        referenceDataService.deleteAnimateur(id);
        return Response.noContent().build();
    }

    /**
     * Rotates the animateur's espace access token (issue #165): the link
     * printed on an already-distributed PDF stops working, the fiche shows the
     * new one. Regeneration is the only way a token ever changes.
     */
    @POST
    @Path("/animateurs/{id}/jeton")
    public Response regenererJetonAnimateur(@PathParam("id") String id) {
        return Response.ok(new JetonAnimateur(referenceDataService.regenererJetonAnimateur(id))).build();
    }

    /** Body of a token regeneration: the new token, nothing else. */
    public record JetonAnimateur(String jeton) {
    }

    @GET
    @Path("/typologies")
    public List<TypologieItem> listTypologies() {
        return referenceDataService.listTypologies();
    }

    @POST
    @Path("/typologies")
    public TypologieItem createTypologie(TypologieItem typologie) {
        return referenceDataService.createTypologie(typologie);
    }

    @PUT
    @Path("/typologies/{id}")
    public TypologieItem updateTypologie(@PathParam("id") String id, TypologieItem typologie) {
        return referenceDataService.updateTypologie(id, typologie);
    }

    /** Returns 400 (rather than a raw FK-violation 500) when the typologie is still assigned to a stand/animateur. */
    @DELETE
    @Path("/typologies/{id}")
    public Response deleteTypologie(@PathParam("id") String id) {
        referenceDataService.deleteTypologie(id);
        return Response.noContent().build();
    }

    @POST
    @Path("/reference-data/import")
    public Response importReferenceData(dev.sylvain.planning.domain.PlanningFestival planning) {
        referenceDataService.importFromPlanning(planning);
        return Response.noContent().build();
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
     * <p>A scenario may also pin {@code decoupageAuto:} (see
     * {@link DecoupageAutoConfig}), applied after {@code parametresDecoupage:}
     * so the découpage it triggers already runs against the parameters the
     * scenario itself pinned — sparing the operator the manual "Découpage"
     * screen round-trip after every import of that scenario. When it does,
     * this returns 200 with an {@link ImportScenarioResult} carrying the
     * target group's name instead of the usual 204, so the frontend can
     * notify the operator that the group it just activated holds the
     * auto-generated vacations.
     */
    /**
     * What a scenario import would touch, for the confirmation dialog shown
     * before either import button runs: replaced referentials, the resolved
     * planning about to be erased, the demandes and locks going with it.
     */
    @GET
    @Path("/reference-data/impact-import")
    public dev.sylvain.planning.service.ReferenceDataRepository.ImpactImport impactImport() {
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
    @Path("/reference-data/cible-scenario")
    public CibleImportView cibleScenario(@QueryParam("name") String name) {
        return versCibleView(planningService.chargerSectionsScenario(name).edition());
    }

    @POST
    @Path("/reference-data/cible-scenario-fichier")
    @Consumes(MediaType.WILDCARD)
    public Response cibleScenarioFichier(String yamlContent) {
        return Response.ok(versCibleView(planningService.chargerEditionTexteScenario(yamlContent))).build();
    }

    @POST
    @Path("/reference-data/import-scenario")
    @Consumes(MediaType.WILDCARD)
    public Response importScenario(@QueryParam("name") String name) {
        return importer(planningService.chargerScenario(name));
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
    private Response importerDansCible(Optional<EditionCibleDto> cibleDto,
            java.util.concurrent.Callable<Boolean> importAction) {
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
     * ReferenceDataRepository#importFromPlanning} auto-derives an id-as-its-
     * own-label typologie entry for every id a stand/animateur references and
     * unconditionally overwrites any existing label when it does — so an
     * explicit {@code {id, label}} pair from the scenario must be applied
     * afterwards to actually stick, not before.
     */
    private void appliquerTypologies(PlanningService.SectionsScenario sections) {
        sections.typologies().forEach(referenceDataService::createTypologie);
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
    @Path("/reference-data/import-scenario-fichier")
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
    @Path("/reference-data/valider-scenario-fichier")
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

    @GET
    @Path("/contraintes-ad-hoc")
    public List<ContrainteAdHoc> listContraintesAdHoc() {
        return referenceDataService.listContraintesAdHoc();
    }

    @POST
    @Path("/contraintes-ad-hoc")
    public Response createContrainteAdHoc(ContrainteAdHoc contrainteAdHoc) {
        return Response.ok(referenceDataService.createContrainteAdHoc(contrainteAdHoc)).build();
    }

    @DELETE
    @Path("/contraintes-ad-hoc/{id}")
    public Response deleteContrainteAdHoc(@PathParam("id") String id) {
        referenceDataService.deleteContrainteAdHoc(id);
        return Response.noContent().build();
    }

    @GET
    @Path("/parametres-legaux")
    public ParametresLegaux getParametresLegaux() {
        return referenceDataService.getParametresLegaux();
    }

    /**
     * Saves the legal parameters. Returns 400 with an explanation when a value
     * exceeds its ordre public ceiling (48 h for adults, art. L3121-20; 35 h
     * for minors, art. L3162-1) rather than letting the exception surface as a
     * 500 — the message is shown as-is to the administrator.
     */
    @PUT
    @Path("/parametres-legaux")
    public Response updateParametresLegaux(ParametresLegaux parametres) {
        return Response.ok(referenceDataService.updateParametresLegaux(parametres)).build();
    }


    @GET
    @Path("/parametres-decoupage")
    public ParametresDecoupage getParametresDecoupage() {
        return referenceDataService.getParametresDecoupage();
    }

    @PUT
    @Path("/parametres-decoupage")
    public ParametresDecoupage updateParametresDecoupage(ParametresDecoupage parametres) {
        return referenceDataService.updateParametresDecoupage(parametres);
    }

    @GET
    @Path("/parametres-solveur")
    public ParametresSolveur getParametresSolveur() {
        return referenceDataService.getParametresSolveur();
    }

    /** Saves the solver's default termination duration (Données tab). Returns 400 when the value isn't positive. */
    @PUT
    @Path("/parametres-solveur")
    public Response updateParametresSolveur(ParametresSolveur parametres) {
        return Response.ok(referenceDataService.updateParametresSolveur(parametres)).build();
    }
}
