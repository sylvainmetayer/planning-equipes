package dev.sylvain.planning.service.solve;

import ai.timefold.solver.core.api.solver.Solver;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.ParametresQualite;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.analyse.FeasibilityAnalyzer;
import dev.sylvain.planning.service.analyse.PlanningDiagnosticService;
import dev.sylvain.planning.service.referentiel.HoraireStandResolver;
import dev.sylvain.planning.service.referentiel.ReferenceData;
import dev.sylvain.planning.service.scenario.ScenarioYamlReader;
import dev.sylvain.planning.service.scenario.ScenarioYamlWriter;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class PlanningService {

    /** How this deployment configures a solve: factory, termination, weights. */
    private final SolverConfiguration solverConfiguration;

    /** The solve itself, and the server-side preparation that precedes it. */
    private final SolveRunner solveRunner;

    private final ReferenceData referenceDataService;

    /** Everything that turns the edition's reference data into a problem to solve. */
    private final ProblemBuilder problemBuilder;

    /** Everything the application answers about a plan without solving it again. */
    private final PlanningWhatIf whatIf;

    /** The business-facing reading of a solved or persisted plan. */
    private final PlanningDiagnosticService diagnosticService;

    /**
     * Null in the plain (non-CDI) tests, which build this service with
     * {@code new} and never exercise the locks: {@link ProblemBuilder#applyVerrouillages}
     * guards on it. Constructor-injected like everything else, so the five
     * collaborators built below receive the bean itself rather than a lambda
     * reading a field CDI would only fill after this constructor ran.
     */
    private final PlanningPersistenceService planningPersistenceService;

    /** The published plan, for {@code stabiliteDuPlanPublie}; null in a plain-Java harness like the persistence above. */
    private final PlanSnapshotService snapshotService;

    public PlanningService(
            @ConfigProperty(name = "planning.solver.seconds-limit", defaultValue = "120") Long secondsLimit,
            @ConfigProperty(name = "planning.solver.unimproved-seconds-limit", defaultValue = "30")
                    Long unimprovedSecondsLimit,
            @ConfigProperty(
                            name = "planning.contraintes.max-emplacements-par-jour",
                            defaultValue = "" + ParametresQualite.EMPLACEMENTS_DISTINCTS_PAR_JOUR_MAX_PAR_DEFAUT)
                    Integer maxEmplacementsParJour,
            ReferenceData referenceDataService,
            FeasibilityAnalyzer feasibilityAnalyzer,
            PlanningPersistenceService planningPersistenceService,
            PlanSnapshotService snapshotService,
            Config config) {
        this.solverConfiguration = new SolverConfiguration(
                secondsLimit, unimprovedSecondsLimit, maxEmplacementsParJour, referenceDataService, config);
        this.referenceDataService = referenceDataService;
        this.planningPersistenceService = planningPersistenceService;
        this.snapshotService = snapshotService;
        this.solveRunner = new SolveRunner(solverConfiguration, referenceDataService, snapshotService);
        this.problemBuilder = new ProblemBuilder(referenceDataService, planningPersistenceService);
        this.whatIf = new PlanningWhatIf(
                solverConfiguration.diagnosticService(),
                referenceDataService,
                planningPersistenceService,
                solveRunner::prepareProblem);
        this.diagnosticService = new PlanningDiagnosticService(
                solverConfiguration.diagnosticService(),
                solverConfiguration.solutionManager(),
                feasibilityAnalyzer,
                () -> planningPersistenceService.loadPersistedPlanning(),
                solveRunner::prepareProblem);
    }

    public PlanningEvenement buildExample() {
        return buildExample(ScenarioYamlReader.DEFAULT_SCENARIO);
    }

    /**
     * Loads a named scenario from the {@link ScenarioYamlReader#SCENARIOS_DIR} folder. The name is
     * a bare file name (e.g. {@code scenario-complet.yaml}); any path component
     * is rejected so callers cannot escape the scenarios folder.
     */
    public PlanningEvenement buildExample(String scenarioName) {
        try {
            return ScenarioYamlReader.buildPlanning(
                    ScenarioYamlReader.readScenario(ScenarioYamlReader.scenarioPath(scenarioName)),
                    referenceDataService::getParametresLegaux);
        } catch (IOException e) {
            throw new RuntimeException("Erreur lors du chargement du scénario YAML", e);
        }
    }

    /**
     * Small, self-contained scenario used as a fast nominal case (a handful of
     * postes) so the hard-constraint invariant can be checked in seconds. The
     * large {@code scenario-complet.yaml} is the complex performance target
     * solved by {@link #buildExample()}.
     */
    public PlanningEvenement buildSimpleExample() {
        try {
            return ScenarioYamlReader.buildPlanning(
                    ScenarioYamlReader.readScenario(ScenarioYamlReader.SCENARIOS_DIR + "/scenario.yml"),
                    referenceDataService::getParametresLegaux);
        } catch (IOException e) {
            throw new RuntimeException("Erreur lors du chargement du scénario YAML", e);
        }
    }

    // --- Solver configuration: façade over SolverConfiguration --------------

    /** @see SolverConfiguration#effectiveConstraintWeights() */
    public Map<String, Integer> effectiveConstraintWeights() {
        return solverConfiguration.effectiveConstraintWeights();
    }

    /** @see SolverConfiguration#effectiveConstraintWeights(Map) */
    public Map<String, Integer> effectiveConstraintWeights(Map<String, Integer> scenario) {
        return solverConfiguration.effectiveConstraintWeights(scenario);
    }

    // --- Problem building: façade over ProblemBuilder -----------------------

    /** @see ProblemBuilder#buildFromReferenceData() */
    public PlanningEvenement buildFromReferenceData() {
        return problemBuilder.buildFromReferenceData();
    }

    /** @see ProblemBuilder#buildFromReferenceData(Reamorcage) */
    public ProblemBuilder.ProblemeReamorce buildFromReferenceData(Reamorcage reamorcage) {
        return problemBuilder.buildFromReferenceData(reamorcage);
    }

    /** @see ProblemBuilder#buildIncrementalFromReferenceData(ReplanificationScope) */
    public ProblemBuilder.ProblemeIncremental buildIncrementalFromReferenceData(ReplanificationScope scope) {
        return problemBuilder.buildIncrementalFromReferenceData(scope);
    }

    /**
     * Serializes the current reference data — through {@link ScenarioYamlWriter}
     * — into the same YAML shape read by {@link ScenarioYamlReader#buildPlanning}, so the
     * result can be dropped into the
     * {@link ScenarioYamlReader#SCENARIOS_DIR} folder and reloaded as-is.
     *
     * <p>Everything that shapes a solve is written, not only the entities:
     * {@code typologies}, {@code emplacements}, {@code parametresLegaux},
     * {@code parametresDecoupage} and {@code parametresSolveur} — re-importing
     * the file therefore reproduces the very same problem, which is the whole
     * point of exporting it. A file missing those sections silently fell back to
     * the importing instance's own settings (its solve duration, its vacation
     * lengths, its relay families), so the "same" scenario replayed elsewhere
     * solved a different problem.</p>
     *
     * <p><b>The créneaux are always written as they are, with their seat list.</b>
     * There used to be a second shape — a découpé edition exporting its source
     * amplitudes plus {@code decoupageAuto} and no {@code postes}, so the import
     * re-ran the découpage — and this javadoc still described it long after
     * issue #172 removed it. Once the découpage has run, the amplitudes it
     * consumed are gone: a découpé edition has nothing but its vacations left to
     * export. The hand-maintained "amplitudes + {@code decoupageAuto}" scenario
     * file stays the source of truth for re-slicing, never this export.</p>
     */
    public String exportScenarioYaml() {
        List<Animateur> animateurs = referenceDataService.listAnimateurs();
        // Raw stands, so the file gets the recurring horaires as rules rather
        // than the few hundred dated windows they expand to — the resolution
        // still runs, because the seat list does depend on it.
        List<Stand> stands = referenceDataService.listStands();
        List<Creneau> creneaux = referenceDataService.listCreneaux();
        if (animateurs.isEmpty() || stands.isEmpty() || creneaux.isEmpty()) {
            throw new IllegalStateException(
                    "Aucune donnée de référence à exporter. Créez des stands, des animateurs et des créneaux d'abord.");
        }
        HoraireStandResolver.apply(stands, creneaux);

        // The edition's créneaux are exported as-is (issue #172): once the
        // découpage ran, the amplitudes it consumed are gone, so a découpé
        // edition exports its vacations plainly — the hand-maintained
        // "amplitudes + decoupageAuto:" scenario file stays the source of
        // truth for re-slicing, never this export.
        List<PosteAffectation> postes = ProblemBuilder.buildPostes(stands, creneaux);
        return ScenarioYamlWriter.buildScenarioYaml(new ScenarioYamlWriter.ScenarioExport(
                animateurs,
                stands,
                creneaux,
                postes,
                referenceDataService.listTypologies(),
                referenceDataService.listEmplacements(),
                referenceDataService.getParametresLegaux(),
                referenceDataService.getParametresDecoupage(),
                referenceDataService.getParametresSolveur(),
                referenceDataService.getContraintesDesactivees(),
                referenceDataService.getConstraintWeights(),
                referenceDataService.snapshotContraintes()));
    }

    // --- Scenario reading: façade over ScenarioYamlReader -------------------
    //
    // The parsing itself lives in ScenarioYamlReader, which is static and needs
    // no database. What stays here is the one thing it cannot know on its own:
    // the edition's ParametresLegaux, the fallback for a file that pins none.

    /** @see ScenarioYamlReader#listScenarios() */
    public List<String> listScenarios() {
        return ScenarioYamlReader.listScenarios();
    }

    /** @see ScenarioYamlReader#buildFromScenarioText */
    public ScenarioYamlReader.ScenarioImporte buildFromScenarioText(String yamlContent) {
        return ScenarioYamlReader.buildFromScenarioText(yamlContent, referenceDataService::getParametresLegaux);
    }

    /** @see ScenarioYamlReader#loadScenarioSections */
    public ScenarioYamlReader.ScenarioSections loadScenarioSections(String scenarioName) {
        return ScenarioYamlReader.loadScenarioSections(scenarioName);
    }

    /** @see ScenarioYamlReader#loadScenario */
    public ScenarioYamlReader.ScenarioImporte loadScenario(String scenarioName) {
        return ScenarioYamlReader.loadScenario(scenarioName, referenceDataService::getParametresLegaux);
    }

    /** @see ScenarioYamlReader#loadEditionScenarioText */
    public Optional<dev.sylvain.planning.scenario.dto.EditionCibleDto> loadEditionScenarioText(String yamlContent) {
        return ScenarioYamlReader.loadEditionScenarioText(yamlContent);
    }

    /** @see ScenarioYamlReader#loadReferenceScenario(String) */
    ScenarioYamlReader.ReferenceScenario loadReferenceScenario(String scenarioName) throws IOException {
        return ScenarioYamlReader.loadReferenceScenario(scenarioName);
    }

    // --- What-if: façade over PlanningWhatIf --------------------------------

    /** @see PlanningWhatIf#SUGGESTIONS_PLAFOND_DEFAUT */
    public static final int SUGGESTIONS_PLAFOND_DEFAUT = PlanningWhatIf.SUGGESTIONS_PLAFOND_DEFAUT;

    /** @see PlanningWhatIf#SUGGESTIONS_PLAFOND_MAX */
    public static final int SUGGESTIONS_PLAFOND_MAX = PlanningWhatIf.SUGGESTIONS_PLAFOND_MAX;

    /** @see PlanningWhatIf#explainAffectation */
    public PlanningWhatIf.AffectationExplanation explainAffectation(PlanningEvenement solved, String posteId) {
        return whatIf.explainAffectation(solved, posteId);
    }

    /** @see PlanningWhatIf#simulateSwap */
    public PlanningWhatIf.SwapSimulation simulateSwap(
            PlanningEvenement solved, String posteId, String animateurCandidatId) {
        return whatIf.simulateSwap(solved, posteId, animateurCandidatId);
    }

    /** @see PlanningWhatIf#suggererReparations */
    public PlanningWhatIf.SuggestionsReparation suggererReparations(
            PlanningEvenement solved, String posteId, Integer plafondDemande) {
        return whatIf.suggererReparations(solved, posteId, plafondDemande);
    }

    /** @see PlanningWhatIf#creneauAvailability */
    public PlanningWhatIf.CreneauAvailability creneauAvailability(
            PlanningEvenement solved, Long creneauId, String standId, String posteId) {
        return whatIf.creneauAvailability(solved, creneauId, standId, posteId);
    }

    /** @see PlanningWhatIf#persistedCreneauAvailability */
    public PlanningWhatIf.CreneauAvailability persistedCreneauAvailability(
            Long creneauId, String standId, String posteId) {
        return whatIf.persistedCreneauAvailability(creneauId, standId, posteId);
    }

    /** @see PlanningWhatIf#applyReparation */
    public void applyReparation(String posteId, String animateurId) {
        whatIf.applyReparation(posteId, animateurId);
    }

    /** @see PlanningWhatIf#applyReparations */
    public void applyReparations(PlanningEvenement persiste, List<String> posteIds, String animateurId) {
        whatIf.applyReparations(persiste, posteIds, animateurId);
    }

    /** @see PlanningWhatIf#simulateEchange */
    public PlanningWhatIf.EchangeSimulation simulateEchange(
            PlanningEvenement solved, String demandeurId, String cibleId, long creneauId, String standId) {
        return whatIf.simulateEchange(solved, demandeurId, cibleId, creneauId, standId);
    }

    /** @see PlanningWhatIf#simulateDeplacement */
    public PlanningWhatIf.DeplacementSimulation simulateDeplacement(
            PlanningEvenement solved, String posteSourceId, String posteCibleId, String animateurCibleId) {
        return whatIf.simulateDeplacement(solved, posteSourceId, posteCibleId, animateurCibleId);
    }

    /** @see PlanningWhatIf#simulateDirectedEchange */
    public PlanningWhatIf.EchangeSimulation simulateDirectedEchange(
            PlanningEvenement solved,
            String demandeurId,
            String cibleId,
            long creneauId,
            String standId,
            long creneauCibleId,
            String standCibleId) {
        return whatIf.simulateDirectedEchange(
                solved, demandeurId, cibleId, creneauId, standId, creneauCibleId, standCibleId);
    }

    /** @see PlanningWhatIf#suggererEchanges */
    public PlanningWhatIf.SuggestionsEchange suggererEchanges(
            PlanningEvenement solved, String demandeurId, long creneauId, String standId, Integer plafondDemande) {
        return whatIf.suggererEchanges(solved, demandeurId, creneauId, standId, plafondDemande);
    }

    // --- Solve: façade over SolveRunner -------------------------------------

    /** @see SolveRunner#solve(PlanningEvenement) */
    public PlanningEvenement solve(PlanningEvenement problem) {
        return solveRunner.solve(problem);
    }

    /** @see SolveRunner#solve(PlanningEvenement, Long) */
    public PlanningEvenement solve(PlanningEvenement problem, Long secondsLimitOverride) {
        return solveRunner.solve(problem, secondsLimitOverride);
    }

    /** @see SolveRunner#solve(PlanningEvenement, Long, Consumer) */
    public PlanningEvenement solve(
            PlanningEvenement problem, Long secondsLimitOverride, Consumer<Solver<PlanningEvenement>> onSolverReady) {
        return solveRunner.solve(problem, secondsLimitOverride, onSolverReady);
    }

    /** @see SolveRunner#solveUntilFeasible */
    public PlanningEvenement solveUntilFeasible(PlanningEvenement problem, long secondsLimitSecurite) {
        return solveRunner.solveUntilFeasible(problem, secondsLimitSecurite);
    }

    /** @see SolveRunner#prepareForAnalysis */
    void prepareForAnalysis(PlanningEvenement planning) {
        solveRunner.prepareForAnalysis(planning);
    }

    // --- Diagnostic: façade over PlanningDiagnosticService ------------------

    /** @see PlanningDiagnosticService#diagnosePersistedPlan() */
    public PlanningDiagnosticService.PlanningDiagnostic diagnosePersistedPlan() {
        return diagnosticService.diagnosePersistedPlan();
    }

    /** @see PlanningDiagnosticService#diagnose(PlanningEvenement) */
    public PlanningDiagnosticService.PlanningDiagnostic diagnose(PlanningEvenement solved) {
        return diagnosticService.diagnose(solved);
    }
}
