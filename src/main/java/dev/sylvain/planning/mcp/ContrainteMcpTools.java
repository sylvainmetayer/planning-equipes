package dev.sylvain.planning.mcp;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import dev.sylvain.planning.service.ConstraintAnalysisStore;
import dev.sylvain.planning.service.ConstraintAnalysisStore.StoredAnalysis;
import dev.sylvain.planning.service.PlanningService.ConstraintDiagnostic;
import dev.sylvain.planning.service.ReferenceDataService;
import dev.sylvain.planning.solver.ConstraintCatalog;
import dev.sylvain.planning.solver.ConstraintCatalog.ConstraintDefinition;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;

/**
 * MCP tools mirroring {@code ConstraintResource}: the business catalogue of
 * constraints (what each one means, whether it is active, how it scored on
 * the last analysis) plus activation/désactivation.
 */
@ApplicationScoped
public class ContrainteMcpTools {

    @Inject
    ConstraintAnalysisStore analysisStore;

    @Inject
    ReferenceDataService referenceDataService;

    @Tool(description = "Liste le catalogue métier des contraintes du solveur : niveau (HARD/MEDIUM/SOFT), "
            + "description, si elle est active, et son score/nombre de correspondances lors de la dernière analyse.")
    List<ContrainteView> lister_contraintes() {
        StoredAnalysis analysis = analysisStore.latest();
        Map<String, ConstraintDiagnostic> byName = analysis == null
                ? Map.of()
                : analysis.diagnostic().contraintes().stream()
                        .collect(Collectors.toMap(ConstraintDiagnostic::name, Function.identity(),
                                (first, second) -> first));
        Set<String> desactivees = referenceDataService.getContraintesDesactivees();

        return ConstraintCatalog.definitions().stream()
                .map(definition -> toView(definition, byName.get(definition.name()), desactivees))
                .toList();
    }

    @Tool(description = "Active une contrainte pour le prochain solve (annule une désactivation précédente).")
    ToggleResult activer_contrainte(@ToolArg(description = "Nom technique de la contrainte (voir lister_contraintes)") String nom) {
        return setActive(nom, true);
    }

    @Tool(description = "Désactive une contrainte pour le prochain solve. Le solveur l'ignorera jusqu'à réactivation.")
    ToggleResult desactiver_contrainte(@ToolArg(description = "Nom technique de la contrainte (voir lister_contraintes)") String nom) {
        return setActive(nom, false);
    }

    private ToggleResult setActive(String nom, boolean actif) {
        boolean known = ConstraintCatalog.definitions().stream()
                .anyMatch(definition -> definition.name().equals(nom));
        if (!known) {
            throw new NotFoundException("Contrainte inconnue : " + nom);
        }
        referenceDataService.setContrainteActive(nom, actif);
        return new ToggleResult(nom, actif);
    }

    public record ToggleResult(String nom, boolean actif) {
    }

    static ContrainteView toView(ConstraintDefinition definition, ConstraintDiagnostic diagnostic,
            Set<String> desactivees) {
        return new ContrainteView(
                definition.name(),
                definition.niveau().name(),
                definition.categorie(),
                definition.description(),
                !desactivees.contains(definition.name()),
                diagnostic == null ? null : diagnostic.score(),
                diagnostic == null ? null : diagnostic.matchCount());
    }

    public record ContrainteView(String nom, String niveau, String categorie, String description, boolean actif,
            String score, Integer nombreCorrespondances) {
    }
}
