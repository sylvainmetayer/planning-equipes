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
import dev.sylvain.planning.service.PlanningService;
import dev.sylvain.planning.service.PlanningDiagnosticService.ConstraintDiagnostic;
import dev.sylvain.planning.service.PlanningDiagnosticService.ContributionAdHoc;
import dev.sylvain.planning.service.ReferenceDataService;
import dev.sylvain.planning.service.journal.CurrentAction;
import dev.sylvain.planning.solver.ConstraintCatalog;
import dev.sylvain.planning.solver.ConstraintCatalog.ConstraintDefinition;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
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
@Path("/constraints")
@Produces(MediaType.APPLICATION_JSON)
public class ConstraintResource {

    @Inject
    ConstraintAnalysisStore analysisStore;

    @Inject
    ReferenceDataService referenceDataService;

    /** Says which way the toggle went; see setActif. */
    @Inject
    CurrentAction currentAction;

    @Inject
    PlanningService planningService;

    @GET
    public ConstraintsView list() {
        return view(analysisStore.latest());
    }

    /**
     * Re-derives the analysis from the plan currently persisted and returns the
     * refreshed view. No solver is started: this is one score calculation over
     * the plan already on screen, where refreshing this screen used to mean a
     * full solve whose result was thrown away — minutes of solver time to
     * describe a plan nobody would ever see.
     *
     * <p>Returns the empty view (no {@code analysedAt}, no score) when nothing
     * is persisted yet, which is exactly what the screen has always shown
     * before the first solve.</p>
     */
    @POST
    @Path("/diagnostic")
    @Consumes(MediaType.WILDCARD)
    public ConstraintsView diagnose() {
        return view(analysisStore.refreshFromPersistedPlan());
    }

    private ConstraintsView view(StoredAnalysis analysis) {
        Map<String, ConstraintDiagnostic> byName = analysis == null
                ? Map.of()
                : analysis.diagnostic().contraintes().stream()
                        .collect(Collectors.toMap(ConstraintDiagnostic::name, Function.identity(),
                                (first, second) -> first));
        Set<String> desactivees = referenceDataService.getContraintesDesactivees();
        Map<String, Integer> poids = planningService.effectiveConstraintWeights();

        List<ConstraintView> constraints = ConstraintCatalog.definitions().stream()
                .map(definition -> toView(definition, byName.get(definition.name()), desactivees, poids))
                .toList();

        return new ConstraintsView(
                analysis == null ? null : analysis.analysedAt(),
                analysis == null ? null : analysis.diagnostic().score(),
                analysis == null ? null : analysis.diagnostic().postesNonPourvus(),
                analysis == null ? null : analysis.diagnostic().faisabilite(),
                analysis == null ? null : analysis.diagnostic().hardScore(),
                constraints,
                analysis == null ? List.of() : analysis.diagnostic().contraintesAdHocEnCause());
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
        requireKnown(name);
        referenceDataService.setContrainteActive(name, update.actif());
        // One method, two actions: the history keys on the route, so without
        // this it would record « Contrainte activée » for a deactivation —
        // the one thing a journal must never do (issue #406).
        currentAction.action(update.actif() ? "CONTRAINTE_ACTIVEE" : "CONTRAINTE_DESACTIVEE");
        return update;
    }

    /**
     * Sets the weight of one constraint <b>for the current edition</b>, or
     * drops that override when {@code poids} is null. The weights configured
     * in {@code application.properties} stay the default for every edition
     * that never touched them; this is what makes the dosage of the « Qualité
     * d'organisation » rules a per-event decision rather than a
     * per-deployment one.
     */
    @PUT
    @Path("/{name}/poids")
    @Consumes(MediaType.APPLICATION_JSON)
    public ConstraintPoidsUpdate setPoids(@PathParam("name") String name, ConstraintPoidsUpdate update) {
        requireKnown(name);
        referenceDataService.setConstraintWeight(name, update.poids());
        return new ConstraintPoidsUpdate(planningService.effectiveConstraintWeights().getOrDefault(name, 1));
    }

    /** 404 rather than a silently stored row when the name matches no constraint of the catalogue. */
    private static void requireKnown(String name) {
        boolean known = ConstraintCatalog.definitions().stream()
                .anyMatch(definition -> definition.name().equals(name));
        if (!known) {
            throw new NotFoundException("Unknown constraint: " + name);
        }
    }

    private ConstraintView toView(ConstraintDefinition definition, ConstraintDiagnostic diagnostic,
            Set<String> desactivees, Map<String, Integer> poids) {
        return new ConstraintView(
                definition.name(),
                definition.niveau().name(),
                definition.categorie(),
                definition.description(),
                !desactivees.contains(definition.name()),
                definition.protegee(),
                definition.dosable(),
                poids.getOrDefault(definition.name(), 1),
                diagnostic == null ? null : diagnostic.score(),
                diagnostic == null ? null : diagnostic.matchCount(),
                diagnostic == null ? List.of() : diagnostic.violations());
    }

    /**
     * @param actif       whether the constraint is applied on the next solve
     * @param protegee    whether this rule founds the plan in law or in the
     *                    minors' safety policy — the UI confirms before
     *                    switching one off (see
     *                    {@code ConstraintCatalog.CATEGORIES_PROTEGEES})
     * @param dosable     whether this rule is one of those meant to be dosed
     *                    rather than switched off (the MEDIUM rules of
     *                    « Qualité d'organisation »)
     * @param poids       weight applied to a single match of this constraint
     *                    on the next solve: the deployment default, overridden
     *                    by what this edition stored
     * @param score       score contributed by this constraint on the last
     *                    analysis, {@code null} when never analysed
     * @param matchCount  number of times the rule matched (i.e. was violated
     *                    or rewarded) on that same run
     * @param violations  one human-readable line per match, only for
     *                    constraints enforced at {@code Niveau.HARD} — always
     *                    empty for medium/soft ones (see
     *                    {@code ConstraintCatalog.NOMS_DURS})
     */
    public record ConstraintView(
            String name,
            String niveau,
            String categorie,
            String description,
            boolean actif,
            boolean protegee,
            boolean dosable,
            int poids,
            String score,
            Integer matchCount,
            List<String> violations) {
    }

    /**
     * @param actif whether the constraint is applied on the next solve
     */
    public record ConstraintToggleUpdate(boolean actif) {
    }

    /**
     * @param poids the weight one match of the constraint is worth on the next
     *              solve, or {@code null} to drop this edition's override and
     *              fall back to the configured default
     */
    public record ConstraintPoidsUpdate(Integer poids) {
    }

    /**
     * @param hardScore the hard score actually reached by the last analysed
     *                  solve, {@code null} when never analysed. Distinct from
     *                  {@code faisabilite}: that field is a cheap, optimistic
     *                  pre-solve capacity estimate that can say "réalisable"
     *                  for a plan the solver still could not bring to zero
     *                  hard (see {@code FeasibilityAnalyzer}'s javadoc) — the
     *                  UI must check {@code hardScore == 0}, not just
     *                  {@code faisabilite.feasible}, to know whether the plan
     *                  actually in hand is fully legal/staffed.
     * @param contraintesAdHocEnCause the hand-entered exceptions the last
     *                  analysis found still violated, most violated first —
     *                  what turns "affectationForcee: 12" into a list of
     *                  exceptions to arbitrate (issue #84). Empty when the
     *                  plan honours all of them, and when nothing was ever
     *                  analysed.
     */
    public record ConstraintsView(
            Instant analysedAt,
            String scoreGlobal,
            Integer postesNonPourvus,
            FeasibilityReport faisabilite,
            Integer hardScore,
            List<ConstraintView> contraintes,
            List<ContributionAdHoc> contraintesAdHocEnCause) {
    }
}
