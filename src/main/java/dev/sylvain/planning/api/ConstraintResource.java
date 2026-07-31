package dev.sylvain.planning.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import dev.sylvain.planning.service.ConstraintAnalysisStore;
import dev.sylvain.planning.service.ConstraintAnalysisStore.StoredAnalysis;
import dev.sylvain.planning.service.FeasibilityAnalyzer.FeasibilityReport;
import dev.sylvain.planning.service.PlanningService.ConstraintDiagnostic;
import dev.sylvain.planning.service.ReferenceDataService;
import dev.sylvain.planning.solver.ConstraintCatalog;
import dev.sylvain.planning.solver.ConstraintCatalog.ConstraintDefinition;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Business view of the active constraints: what each rule means, at which
 * level it is enforced, whether it is active, and how it scored during the
 * last analysis.
 */
@Path("/api/constraints")
@Produces(MediaType.APPLICATION_JSON)
public class ConstraintResource {

    @Inject
    ConstraintAnalysisStore analysisStore;

    @Inject
    ReferenceDataService referenceDataService;

    @GET
    public ConstraintsView list() {
        StoredAnalysis analysis = analysisStore.latest();
        Map<String, ConstraintDiagnostic> byName = analysis == null
                ? Map.of()
                : analysis.diagnostic().contraintes().stream()
                        .collect(Collectors.toMap(ConstraintDiagnostic::name, Function.identity(),
                                (first, second) -> first));
        Set<String> desactivees = referenceDataService.getContraintesDesactivees();

        List<ConstraintView> constraints = ConstraintCatalog.definitions().stream()
                .map(definition -> toView(definition, byName.get(definition.name()), desactivees))
                .toList();

        return new ConstraintsView(
                analysis == null ? null : analysis.analysedAt(),
                analysis == null ? null : analysis.diagnostic().score(),
                analysis == null ? null : analysis.diagnostic().postesNonPourvus(),
                analysis == null ? null : analysis.diagnostic().faisabilite(),
                constraints);
    }

    /**
     * Enables or disables a constraint for the next solve. {@code actif=false}
     * stores a toggle in the database; the constraint stays in the catalogue
     * (still shown, still described) but the solver skips it on every solve
     * until it is re-enabled.
     */
    @PUT
    @Path("/{name}")
    @Consumes(MediaType.APPLICATION_JSON)
    public ConstraintToggleUpdate setActif(@PathParam("name") String name, ConstraintToggleUpdate update) {
        boolean known = ConstraintCatalog.definitions().stream()
                .anyMatch(definition -> definition.name().equals(name));
        if (!known) {
            throw new NotFoundException("Unknown constraint: " + name);
        }
        referenceDataService.setContrainteActive(name, update.actif());
        return update;
    }

    private ConstraintView toView(ConstraintDefinition definition, ConstraintDiagnostic diagnostic,
            Set<String> desactivees) {
        return new ConstraintView(
                definition.name(),
                definition.niveau().name(),
                definition.categorie(),
                definition.description(),
                !desactivees.contains(definition.name()),
                diagnostic == null ? null : diagnostic.score(),
                diagnostic == null ? null : diagnostic.matchCount());
    }

    /**
     * @param actif       whether the constraint is applied on the next solve
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
            boolean actif,
            String score,
            Integer matchCount) {
    }

    public record ConstraintToggleUpdate(boolean actif) {
    }

    public record ConstraintsView(
            Instant analysedAt,
            String scoreGlobal,
            Integer postesNonPourvus,
            FeasibilityReport faisabilite,
            List<ConstraintView> contraintes) {
    }
}
