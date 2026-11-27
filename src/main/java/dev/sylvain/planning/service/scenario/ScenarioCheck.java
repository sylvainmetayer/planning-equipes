package dev.sylvain.planning.service.scenario;

import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.scenario.ScenarioBinder;
import dev.sylvain.planning.scenario.ScenarioFormatException;
import dev.sylvain.planning.scenario.ScenarioTruncation;
import dev.sylvain.planning.scenario.ScenarioValidator;
import dev.sylvain.planning.service.BusinessError;
import java.util.ArrayList;
import java.util.List;

/**
 * The report of a scenario file checked without importing it — the « Valider
 * un scénario » screen and the MCP tool, one door for both.
 *
 * <p>Three stages, each run only when the one before found nothing: binding
 * (the shape), Bean Validation (the fields), then the mapping into the domain
 * the import runs before writing anything (the cross-references: a seat
 * naming a stand the file does not define). That last stage is what lets a
 * file this report calls valid be one the import accepts. It reads nothing of
 * any edition: the legal settings it is given are the defaults, which only
 * fill what the file leaves out.</p>
 */
public final class ScenarioCheck {

    private ScenarioCheck() {}

    /**
     * @param erreurs        what the import would refuse, empty when it would not
     * @param avertissements what the import would accept but say: an older format converted, a file
     *                       that looks cut short
     */
    public record Report(List<String> erreurs, List<String> avertissements) {}

    public static Report check(String yamlContent) {
        if (yamlContent == null || yamlContent.isBlank()) {
            return new Report(List.of("Le fichier est vide."), List.of());
        }
        ScenarioBinder.Bound bound;
        try {
            bound = ScenarioBinder.bindWithWarnings(yamlContent);
        } catch (ScenarioFormatException e) {
            // Reported as-is: the binder writes its message for the person who
            // wrote the file, and prefixing it again would only bury it.
            return new Report(List.of(e.getMessage()), List.of());
        }
        List<String> erreurs = ScenarioValidator.violations(bound.scenario());
        if (erreurs.isEmpty()) {
            try {
                ScenarioYamlReader.fromDto(bound.scenario(), ParametresLegaux::new);
            } catch (BusinessError refusal) {
                erreurs = List.of(refusal.getMessage());
            }
        }
        List<String> avertissements = new ArrayList<>(bound.warnings());
        ScenarioTruncation.missingTail(bound.scenario()).ifPresent(avertissements::add);
        return new Report(erreurs, List.copyOf(avertissements));
    }
}
