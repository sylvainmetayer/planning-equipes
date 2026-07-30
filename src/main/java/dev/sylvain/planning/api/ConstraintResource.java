package dev.sylvain.planning.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import dev.sylvain.planning.service.ConstraintAnalysisStore;
import dev.sylvain.planning.service.ConstraintAnalysisStore.StoredAnalysis;
import dev.sylvain.planning.service.FeasibilityAnalyzer.FeasibilityReport;
import dev.sylvain.planning.service.PlanningService.ConstraintDiagnostic;
import dev.sylvain.planning.solver.ConstraintCatalog;
import dev.sylvain.planning.solver.ConstraintCatalog.ConstraintDefinition;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Business view of the active constraints: what each rule means, at which
 * level it is enforced, and how it scored during the last analysis.
 */
@Path("/api/constraints")
@Produces(MediaType.APPLICATION_JSON)
public class ConstraintResource {

    @Inject
    ConstraintAnalysisStore analysisStore;

    @GET
    public ConstraintsView list() {
        StoredAnalysis analysis = analysisStore.latest();
        Map<String, ConstraintDiagnostic> byName = analysis == null
                ? Map.of()
                : analysis.diagnostic().contraintes().stream()
                        .collect(Collectors.toMap(ConstraintDiagnostic::name, Function.identity(),
                                (first, second) -> first));

        List<ConstraintView> constraints = ConstraintCatalog.definitions().stream()
                .map(definition -> toView(definition, byName.get(definition.name())))
                .toList();

        return new ConstraintsView(
                analysis == null ? null : analysis.analysedAt(),
                analysis == null ? null : analysis.diagnostic().score(),
                analysis == null ? null : analysis.diagnostic().postesNonPourvus(),
                analysis == null ? null : analysis.diagnostic().faisabilite(),
                constraints);
    }

    private ConstraintView toView(ConstraintDefinition definition, ConstraintDiagnostic diagnostic) {
        return new ConstraintView(
                definition.name(),
                definition.niveau().name(),
                definition.categorie(),
                definition.description(),
                diagnostic == null ? null : diagnostic.score(),
                diagnostic == null ? null : diagnostic.matchCount());
    }

    /**
     * @param score       score contributed by this constraint on the last
     *                    analysis, {@code null} when never analysed
     * @param matchCount  number of times the rule matched (i.e. was violated
     *                    or rewarded) on that same run
     */
    public record ConstraintView(
            String name,
            String niveau,
            String categorie,
            String description,
            String score,
            Integer matchCount) {
    }

    public record ConstraintsView(
            Instant analysedAt,
            String scoreGlobal,
            Integer postesNonPourvus,
            FeasibilityReport faisabilite,
            List<ConstraintView> contraintes) {
    }
}
