package dev.sylvain.planning.mcp;

import dev.sylvain.planning.domain.Edition;
import dev.sylvain.planning.scenario.ScenarioFormatException;
import dev.sylvain.planning.scenario.ScenarioValidator;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.edition.EditionService;
import dev.sylvain.planning.service.scenario.ScenarioImportService;
import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import dev.sylvain.planning.service.solve.PlanningService;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;

/**
 * MCP tools for the scenario lifecycle of {@code PlanningResource} /
 * {@code ReferenceDataResource}: list the bundled scenarios, import one (or a
 * YAML file's content) into the referential, validate a YAML file without
 * importing it, export the current referential as a scenario, and wipe an
 * edition's data.
 *
 * <p>The two import tools delegate to {@link ScenarioImportService} rather
 * than re-implementing the order it owns (paramètres légaux and solveur pinned
 * by the scenario, then the planning itself, then the optional
 * {@code typologies:}, {@code journeesTypes:} and {@code contraintes:}
 * sections — the order matters, see that class's javadoc). Duplicating it here
 * is exactly how the two would drift. Until #392's A3 they delegated to {@code ReferenceDataResource}
 * instead, which meant reaching for a JAX-RS {@code Response} to read a
 * business outcome.
 *
 * <p>Deliberately <b>not</b> exposed, unlike its REST counterpart
 * {@code GET /api/planning/export-scenario}: the scenario export. That YAML
 * carries every animateur's prénom, nom and date de naissance (it has to —
 * it is meant to be re-importable), which is precisely what issue #107
 * forbids from leaving over MCP. Same reasoning for the database dump of
 * {@code DatabaseResource}, see site/src/docs/comprendre/mcp-connexion.md.
 */
@EditionCiblee
@RefusMetier
@Journalise
@ApplicationScoped
public class ScenarioMcpTools {

    private final PlanningService planningService;

    private final PlanningPersistenceService persistenceService;

    private final ScenarioImportService scenarioImportService;

    private final EditionService editionService;

    @Inject
    ScenarioMcpTools(
            PlanningService planningService,
            PlanningPersistenceService persistenceService,
            ScenarioImportService scenarioImportService,
            EditionService editionService) {
        this.planningService = planningService;
        this.persistenceService = persistenceService;
        this.scenarioImportService = scenarioImportService;
        this.editionService = editionService;
    }

    @Tool(
            name = "lister_scenarios",
            description = "Liste les scénarios d'exemple livrés avec l'application, importables par leur nom"
                    + " (les fichiers gamme-* et extreme-* sont livrés et importables par leur nom, mais non listés).",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    List<String> listScenarios() {
        return planningService.listScenarios();
    }

    @Tool(
            name = "importer_scenario",
            description = "Importe un scénario dans les données de référence de l'édition : un scénario livré "
                    + "par son « nom » (voir lister_scenarios), ou un contenu YAML fourni dans « yaml » (même format "
                    + "que l'export), l'un des deux. Remplace stands, créneaux, animateurs et postes existants, et "
                    + "applique les paramètres que le scénario porte. Opération destructive : previsualiser_import "
                    + "dit ce qui serait écrasé, diagnostiquer_scenario vérifie un YAML sans rien importer. Le "
                    + "scénario peut nommer sa propre édition (section edition:), qui l'emporte sur l'argument ; la "
                    + "réponse dit où les données ont atterri.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = true,
                            idempotentHint = true,
                            openWorldHint = false))
    ImportResult importScenario(
            @ToolArg(
                            description =
                                    "Nom d'un scénario livré (voir lister_scenarios) ; absent quand « yaml » est donné",
                            required = false)
                    String nom,
            @ToolArg(description = "Contenu YAML du scénario ; absent quand « nom » est donné", required = false)
                    String yaml,
            @ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        if ((nom == null || nom.isBlank()) == (yaml == null || yaml.isBlank())) {
            throw new BusinessError.Invalid(
                    "Donner « nom » (un scénario livré, voir lister_scenarios) ou « yaml », l'un des deux et pas"
                            + " les deux.");
        }
        return toImportResult(
                yaml == null || yaml.isBlank()
                        ? scenarioImportService.importBundled(nom)
                        : scenarioImportService.importYaml(yaml));
    }

    @Deprecated
    @Tool(
            name = "importer_scenario_yaml",
            description = "Ancien nom, gardé jusqu'en 5.0 : utiliser importer_scenario (yaml). "
                    + "Importe un scénario fourni sous forme de contenu YAML (même format que l'export). "
                    + "Opération destructive : remplace les données de référence existantes.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = true,
                            idempotentHint = true,
                            openWorldHint = false))
    ImportResult importScenarioYaml(
            @ToolArg(description = "Contenu YAML du scénario") String yaml,
            @ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        return toImportResult(scenarioImportService.importYaml(yaml));
    }

    @Computes
    @Tool(
            name = "diagnostiquer_scenario",
            description =
                    "Vérifie la structure d'un scénario YAML sans rien importer ni lire de l'édition. Renvoie la liste des erreurs "
                            + "trouvées, vide si le fichier est valide.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    ValidationResult diagnoseScenario(@ToolArg(description = "Contenu YAML du scénario") String yaml) {
        return validateScenarioYaml(yaml);
    }

    @Deprecated
    @Computes
    @Tool(
            name = "valider_scenario_yaml",
            description = "Ancien nom, gardé jusqu'en 5.0 : utiliser diagnostiquer_scenario. "
                    + "Valide la structure d'un scénario YAML sans rien importer. Renvoie la liste des erreurs "
                    + "trouvées, vide si le fichier est valide.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    ValidationResult validateScenarioYaml(@ToolArg(description = "Contenu YAML du scénario") String yaml) {
        if (yaml == null || yaml.isBlank()) {
            return new ValidationResult(false, List.of("Le fichier est vide."));
        }
        try {
            List<String> erreurs = ScenarioValidator.validate(yaml);
            return new ValidationResult(erreurs.isEmpty(), erreurs);
        } catch (ScenarioFormatException e) {
            return new ValidationResult(false, List.of(e.getMessage()));
        }
    }

    @Tool(
            name = "supprimer_donnees_edition",
            description = "Vide entièrement UNE ÉDITION : stands, créneaux, animateurs, affectations et "
                    + "contraintes ad hoc de l'édition ciblée, les autres éditions n'y touchent pas. Opération "
                    + "destructive, à ne lancer que sur demande explicite : seul un administrateur peut la défaire, "
                    + "en restaurant la sauvegarde prise juste avant le vidage, référentiel et planning compris.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = true,
                            idempotentHint = true,
                            openWorldHint = false))
    ResetResult deleteEditionData(@ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        return resetData(edition);
    }

    @Deprecated
    @Tool(
            name = "reinitialiser_donnees",
            description = "Ancien nom, gardé jusqu'en 5.0 : utiliser supprimer_donnees_edition. "
                    + "Vide entièrement UNE ÉDITION : stands, créneaux, animateurs, affectations et "
                    + "contraintes ad hoc de l'édition ciblée, les autres éditions n'y touchent pas. Opération "
                    + "destructive, à ne lancer que sur demande explicite : seul un administrateur peut la défaire, "
                    + "en restaurant la sauvegarde prise juste avant le vidage, référentiel et planning compris.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = true,
                            idempotentHint = true,
                            openWorldHint = false))
    ResetResult resetData(@ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        persistenceService.clearDatabase();
        Edition videe = editionService.editionCourante();
        return new ResetResult(
                true,
                "Édition " + videe.getId() + " (" + videe.getNom() + ") vidée : stands, "
                        + "créneaux, animateurs, affectations et contraintes ad hoc.");
    }

    private ImportResult toImportResult(ScenarioImportService.ScenarioImportOutcome outcome) {
        if (outcome.editionId() != null) {
            // The scenario's own `edition:` section wins over the call's
            // `edition` argument, and may even have created the edition it
            // names: an import that says nothing about where it landed is
            // exactly the silence issue #181 closes.
            return new ImportResult(
                    true,
                    outcome.editionId(),
                    outcome.editionNom(),
                    Boolean.TRUE.equals(outcome.editionCreee()),
                    outcome.avertissements());
        }
        // The scenario named no edition, so the data landed wherever the call
        // was already pointing — which the outcome cannot know and this can.
        Edition courante = editionService.editionCourante();
        return new ImportResult(true, courante.getId(), courante.getNom(), false, outcome.avertissements());
    }

    /**
     * @param editionId    the edition the data really landed in: the one the scenario names if it names one,
     *                     otherwise the one of the call
     * @param editionCreee the edition did not exist and has just been created by this import
     * @param avertissements what the conversion of an older file format dropped, one sentence each, naming a
     *                       field or an id, never a person
     */
    public record ImportResult(
            boolean importe, String editionId, String editionNom, boolean editionCreee, List<String> avertissements) {}

    public record ValidationResult(boolean valide, List<String> erreurs) {}

    public record ResetResult(boolean reinitialise, String message) {}
}
