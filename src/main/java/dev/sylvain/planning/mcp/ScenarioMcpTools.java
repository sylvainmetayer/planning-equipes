package dev.sylvain.planning.mcp;

import dev.sylvain.planning.service.BusinessError;
import java.io.IOException;
import java.util.List;

import dev.sylvain.planning.api.ReferenceDataResource.ImportScenarioResult;
import dev.sylvain.planning.domain.Edition;
import dev.sylvain.planning.api.ValidationError;
import dev.sylvain.planning.api.ReferenceDataResource;
import dev.sylvain.planning.scenario.ScenarioValidator;
import dev.sylvain.planning.service.EditionService;
import dev.sylvain.planning.service.PlanningPersistenceService;
import dev.sylvain.planning.service.PlanningService;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

/**
 * MCP tools for the scenario lifecycle of {@code PlanningResource} /
 * {@code ReferenceDataResource}: list the bundled scenarios, import one (or a
 * YAML file's content) into the referential, validate a YAML file without
 * importing it, export the current referential as a scenario, and wipe an
 * edition's data.
 *
 * <p>The two import tools delegate to {@link ReferenceDataResource} rather
 * than re-implementing the orchestration it owns (paramètres légaux /
 * découpage / solveur pinned by the scenario, then the planning itself, then
 * the optional {@code decoupageAuto:} and {@code typologies:} sections, in
 * that order — the order matters, see that class's javadoc). Duplicating it
 * here is exactly how the two would drift.
 *
 * <p>Deliberately <b>not</b> exposed, unlike its REST counterpart
 * {@code GET /api/planning/export-scenario}: the scenario export. That YAML
 * carries every animateur's prénom, nom and date de naissance (it has to —
 * it is meant to be re-importable), which is precisely what issue #107
 * forbids from leaving over MCP. Same reasoning for the database dump of
 * {@code DatabaseResource}, see docs/mcp.md.
 */
@EditionCiblee
@ApplicationScoped
public class ScenarioMcpTools {

    @Inject
    PlanningService planningService;

    @Inject
    PlanningPersistenceService persistenceService;

    @Inject
    ReferenceDataResource referenceDataResource;

    @Inject
    EditionService editionService;

    @Tool(description = "Liste les scénarios livrés avec l'application, importables par leur nom.")
    List<String> lister_scenarios() {
        return planningService.listScenarios();
    }

    @Tool(description = "Importe un scénario livré dans les données de référence : remplace stands, créneaux, "
            + "animateurs et postes existants. Applique aussi les paramètres légaux/découpage/solveur et le "
            + "découpage automatique que le scénario épingle éventuellement. Opération destructive.")
    ImportResult importer_scenario(@ToolArg(description = "Nom du scénario (voir lister_scenarios)") String nom,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        return toImportResult(referenceDataResource.importScenario(nom));
    }

    @Tool(description = "Importe un scénario fourni sous forme de contenu YAML (même format que l'export). "
            + "Opération destructive : remplace les données de référence existantes.")
    ImportResult importer_scenario_yaml(@ToolArg(description = "Contenu YAML du scénario") String yaml,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        Response response = referenceDataResource.importScenarioFile(yaml);
        if (response.getStatus() >= 400) {
            throw new BusinessError.Invalid("Scénario invalide : " + messageErreur(response));
        }
        return toImportResult(response);
    }

    @Tool(description = "Valide la structure d'un scénario YAML sans rien importer. Renvoie la liste des erreurs "
            + "trouvées, vide si le fichier est valide.")
    ValidationResult valider_scenario_yaml(@ToolArg(description = "Contenu YAML du scénario") String yaml) {
        if (yaml == null || yaml.isBlank()) {
            return new ValidationResult(false, List.of("Le fichier est vide."));
        }
        try {
            List<String> erreurs = ScenarioValidator.validate(yaml);
            return new ValidationResult(erreurs.isEmpty(), erreurs);
        } catch (IOException e) {
            String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return new ValidationResult(false, List.of("YAML invalide : " + message));
        }
    }

    @Tool(description = "Vide entièrement UNE ÉDITION : stands, créneaux, animateurs, affectations et "
            + "contraintes ad hoc de l'édition ciblée, les autres éditions n'y touchent pas. Opération "
            + "destructive et irréversible, à ne lancer que sur demande explicite.")
    ResetResult reinitialiser_donnees(
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        persistenceService.clearDatabase();
        Edition videe = editionService.editionCourante();
        return new ResetResult(true, "Édition " + videe.getId() + " (" + videe.getNom() + ") vidée : stands, "
                + "créneaux, animateurs, affectations et contraintes ad hoc.");
    }

    private ImportResult toImportResult(Response response) {
        if (response.getEntity() instanceof ImportScenarioResult result && result.editionId() != null) {
            // The scenario's own `edition:` section wins over the call's
            // `edition` argument, and may even have created the edition it
            // names: an import that says nothing about where it landed is
            // exactly the silence issue #181 closes.
            return new ImportResult(true, result.decoupageAuto(), result.editionId(), result.editionNom(),
                    Boolean.TRUE.equals(result.editionCreee()));
        }
        boolean decoupageAuto = response.getEntity() instanceof ImportScenarioResult result
                && result.decoupageAuto();
        Edition courante = editionService.editionCourante();
        return new ImportResult(true, decoupageAuto, courante.getId(), courante.getNom(), false);
    }

    private static String messageErreur(Response response) {
        Object entity = response.getEntity();
        if (entity instanceof ValidationError erreur) {
            return erreur.message();
        }
        return String.valueOf(entity);
    }

    /**
     * @param decoupageAuto  the scenario carried a {@code decoupageAuto:} section, so its opening spans were
     *                       sliced into shifts at import time
     * @param editionId      the edition the data really landed in: the one the scenario names if it names one,
     *                       otherwise the one of the call
     * @param editionCreee   the edition did not exist and has just been created by this import
     */
    public record ImportResult(boolean importe, boolean decoupageAuto, String editionId, String editionNom,
            boolean editionCreee) {
    }

    public record ValidationResult(boolean valide, List<String> erreurs) {
    }

    public record ResetResult(boolean reinitialise, String message) {
    }
}
