package dev.sylvain.planning.api;

import dev.sylvain.planning.service.analyse.FeasibilityAnalyzer.FeasibilityReport;
import dev.sylvain.planning.service.analyse.PlanningDiagnosticService.ConstraintDiagnostic;
import dev.sylvain.planning.service.analyse.PlanningDiagnosticService.ConstraintFloor;
import dev.sylvain.planning.service.analyse.PlanningDiagnosticService.ContributionAdHoc;
import dev.sylvain.planning.service.journal.CurrentAction;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.solve.ConstraintAnalysisStore;
import dev.sylvain.planning.service.solve.ConstraintAnalysisStore.StoredAnalysis;
import dev.sylvain.planning.service.solve.PlanningService;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

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
                        .collect(Collectors.toMap(
                                ConstraintDiagnostic::name, Function.identity(), (first, second) -> first));
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
                analysis == null ? List.of() : analysis.diagnostic().contraintesAdHocEnCause(),
                analysis == null ? null : analysis.diagnostic().scoreHorsPlancher(),
                analysis == null ? null : analysis.diagnostic().plancherMedium(),
                analysis == null ? null : analysis.diagnostic().plancherSoft());
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
        return new ConstraintPoidsUpdate(
                planningService.effectiveConstraintWeights().getOrDefault(name, 1));
    }

    /** 404 rather than a silently stored row when the name matches no constraint of the catalogue. */
    private static void requireKnown(String name) {
        boolean known = ConstraintCatalog.definitions().stream()
                .anyMatch(definition -> definition.name().equals(name));
        if (!known) {
            throw new NotFoundException("Unknown constraint: " + name);
        }
    }

    private ConstraintView toView(
            ConstraintDefinition definition,
            ConstraintDiagnostic diagnostic,
            Set<String> desactivees,
            Map<String, Integer> poids) {
        return new ConstraintView(
                definition.name(),
                definition.niveau().name(),
                definition.categorie(),
                definition.description(),
                !desactivees.contains(definition.name()),
                definition.protegee(),
                definition.legale(),
                definition.dosable(),
                poids.getOrDefault(definition.name(), 1),
                diagnostic == null ? null : diagnostic.score(),
                diagnostic == null ? null : diagnostic.matchCount(),
                diagnostic == null ? List.of() : diagnostic.violations(),
                diagnostic == null ? null : diagnostic.postesEvalues(),
                diagnostic == null ? null : diagnostic.plancher());
    }

    /**
     * @param actif       whether the constraint is applied on the next solve
     * @param protegee    whether this rule founds the plan in law, in the
     *                    minors' safety policy, or in the meal rule the event
     *                    is built on — the UI confirms before switching one off
     *                    (see {@code ConstraintCatalog.CATEGORIES_PROTEGEES})
     * @param legale whether an article of the Code du travail is what
     *                    founds it. A protected rule that is not — the minors'
     *                    safety policy, the meal break — is no less binding on
     *                    the organiser, but switching it off does not make the
     *                    plan unlawful, and the confirmation says so in its own
     *                    words rather than over-claiming (see
     *                    {@code ConstraintCatalog.CATEGORIES_LEGALES})
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
     * @param postesEvalues how many items the rule evaluated on that run, at
     *                    the rule's own grain (seats, stand × créneau groups,
     *                    consecutive pairs…) — the denominator of
     *                    {@code plancher.ratio}; {@code null} for a hard rule,
     *                    for a rule whose match count is not per item, and
     *                    when never analysed
     * @param plancher    set when the rule matched at least 95 % of what it
     *                    evaluated (issue #495): its points are a constant no
     *                    solve will move — a floor — and the reading names the
     *                    missing referential data when one explains it, with
     *                    the screen to enter it. Reported, never acted on:
     *                    the rule stays active
     */
    @Schema(requiredProperties = {"actif", "dosable", "legale", "poids", "protegee"})
    public record ConstraintView(
            String name,
            String niveau,
            String categorie,
            String description,
            boolean actif,
            boolean protegee,
            boolean legale,
            boolean dosable,
            int poids,
            String score,
            Integer matchCount,
            List<String> violations,
            Integer postesEvalues,
            ConstraintFloor plancher) {}

    /**
     * @param actif whether the constraint is applied on the next solve
     */
    @Schema(requiredProperties = {"actif"})
    public record ConstraintToggleUpdate(boolean actif) {}

    /**
     * @param poids the weight one match of the constraint is worth on the next
     *              solve, or {@code null} to drop this edition's override and
     *              fall back to the configured default
     */
    public record ConstraintPoidsUpdate(Integer poids) {}

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
     * @param scoreHorsPlancher the score with the floors taken out — the raw
     *                  score minus, level by level, what the constraints
     *                  flagged with a {@code plancher} cost. Same format as
     *                  {@code scoreGlobal}, equal to it when nothing is a
     *                  floor: the part of the score a solve can move, the one
     *                  to compare between two runs
     * @param plancherMedium the constant part of the medium score, signed like
     *                  the score ({@code -5000} for five thousand points no
     *                  solve will recover); {@code plancherSoft} likewise
     */
    public record ConstraintsView(
            Instant analysedAt,
            String scoreGlobal,
            Integer postesNonPourvus,
            FeasibilityReport faisabilite,
            Integer hardScore,
            List<ConstraintView> contraintes,
            List<ContributionAdHoc> contraintesAdHocEnCause,
            String scoreHorsPlancher,
            Integer plancherMedium,
            Integer plancherSoft) {}
}
