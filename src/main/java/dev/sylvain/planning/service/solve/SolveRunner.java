package dev.sylvain.planning.service.solve;

import ai.timefold.solver.core.api.solver.Solver;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.score.director.ScoreDirectorFactoryConfig;
import ai.timefold.solver.core.config.solver.SolverConfig;
import ai.timefold.solver.core.config.solver.termination.TerminationConfig;
import dev.sylvain.planning.domain.AffectationPubliee;
import dev.sylvain.planning.domain.ConstraintToggle;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PastHorizon;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.QuotaTypologie;
import dev.sylvain.planning.service.referentiel.ReferenceData;
import dev.sylvain.planning.solver.PlanningConstraintProvider;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Runs a solve, and fills the problem with the server-side facts first.
 *
 * <p>That preparation is the point: ad hoc constraints, legal parameters,
 * constraint toggles, quality caps, published plan and weights are always
 * overwritten from the edition, never taken from what a caller sent — a
 * planning posted by a client cannot loosen a threshold or decide what people
 * were told. The same preparation runs before a what-if or a diagnostic, which
 * is why it is handed to them as a {@link Consumer}.</p>
 *
 * <p>Split out of {@link PlanningService}, which keeps the four solve entry
 * points as a façade.</p>
 */
final class SolveRunner {

    private final SolverConfiguration configuration;
    private final ReferenceData referenceDataService;

    /** The published plan; {@code null} in the plain-Java harnesses that build {@link PlanningService} with {@code new}. */
    private final PlanSnapshotService snapshots;

    /**
     * The moment the past is judged against (ADR 0044), read at every
     * preparation; {@code null} when the freeze is off. See {@link FrozenPast}.
     */
    private final Supplier<PastHorizon> horizon;

    SolveRunner(SolverConfiguration configuration, ReferenceData referenceDataService, PlanSnapshotService snapshots) {
        this(configuration, referenceDataService, snapshots, () -> null);
    }

    SolveRunner(
            SolverConfiguration configuration,
            ReferenceData referenceDataService,
            PlanSnapshotService snapshots,
            Supplier<PastHorizon> horizon) {
        this.configuration = configuration;
        this.referenceDataService = referenceDataService;
        this.snapshots = snapshots;
        this.horizon = horizon;
    }

    public PlanningEvenement solve(PlanningEvenement problem) {
        return solve(problem, null);
    }

    public PlanningEvenement solve(PlanningEvenement problem, Long secondsLimitOverride) {
        return solve(problem, secondsLimitOverride, null);
    }

    /**
     * Same as {@link #solve(PlanningEvenement, Long)}, but hands the freshly
     * built {@link Solver} to {@code onSolverReady} before blocking on
     * {@code solve()} — the only way a caller running this on a background
     * thread (see {@code SolverJobService}) can later call
     * {@link Solver#terminateEarly()} to stop a solve started by mistake.
     */
    public PlanningEvenement solve(
            PlanningEvenement problem, Long secondsLimitOverride, Consumer<Solver<PlanningEvenement>> onSolverReady) {
        prepareProblem(problem);
        FrozenPast.pin(problem.getPostes());
        Solver<PlanningEvenement> solver = configuration
                .resolveSolverFactory(secondsLimitOverride, problem)
                .buildSolver();
        if (onSolverReady != null) {
            onSolverReady.accept(solver);
        }
        return solver.solve(problem);
    }

    /**
     * Solves until the plan becomes hard-feasible (or {@code secondsLimitSecurite}
     * elapses, whichever comes first), instead of spending a full time budget on
     * medium/soft polishing. Large scenarios (e.g. {@code scenario-complet.yaml},
     * ~2000 postes) reach hard-feasibility in well under a minute but keep
     * improving medium/soft for the rest of a production-sized budget; a caller
     * that only cares about the hard score (e.g. a regression test) would
     * otherwise wait out that whole budget for nothing.
     */
    public PlanningEvenement solveUntilFeasible(PlanningEvenement problem, long secondsLimitSecurite) {
        prepareProblem(problem);
        FrozenPast.pin(problem.getPostes());
        SolverConfig solverConfig = SolverConfig.createFromXmlResource("solver/solverConfig.xml");
        solverConfig.setScoreDirectorFactoryConfig(
                new ScoreDirectorFactoryConfig().withConstraintProviderClass(PlanningConstraintProvider.class));
        TerminationConfig termination = new TerminationConfig();
        termination.setSecondsSpentLimit(secondsLimitSecurite);
        termination.setBestScoreFeasible(true);
        solverConfig.setTerminationConfig(termination);
        if (LargeProblemConstruction.applies(problem)) {
            LargeProblemConstruction.adapt(solverConfig);
        }
        Solver<PlanningEvenement> solver =
                SolverFactory.<PlanningEvenement>create(solverConfig).buildSolver();
        return solver.solve(problem);
    }

    /**
     * Fills a planning read from the database with the server-side facts before
     * anything scores it — the same overwrite a solve does. Package-private for
     * {@link DeplacementService}, which must judge a gesture on the rules the
     * edition really runs under.
     */
    void prepareForAnalysis(PlanningEvenement planning) {
        prepareProblem(planning);
    }

    void prepareProblem(PlanningEvenement problem) {
        // The past, as a fact of the score (ADR 0044): marked on every
        // preparation, so the analyses of the persisted plan read the same
        // « counted, never reproached » a solve does. Against the horizon the
        // problem was built under when it carries one — the clock has moved
        // since the build, the seats it re-seeded and pinned have not — and
        // against the clock, read once and kept, for a problem that came
        // without one. Pinning is the solve entry points' business: a
        // diagnostic moves nothing.
        PastHorizon moment = problem.getPastHorizon();
        if (moment == null) {
            moment = horizon.get();
            problem.setPastHorizon(moment);
        }
        if (moment != null && problem.getPostes() != null) {
            FrozenPast.mark(problem.getPostes(), moment);
        }
        if (problem.getContraintesAdHoc() == null
                || problem.getContraintesAdHoc().isEmpty()) {
            problem.setContraintesAdHoc(referenceDataService.snapshotContraintes());
        }
        if (problem.getParametresLegaux() == null
                || problem.getParametresLegaux().isEmpty()) {
            problem.setParametresLegaux(List.of(referenceDataService.getParametresLegaux()));
        }
        if (problem.getFenetresRepas() == null || problem.getFenetresRepas().isEmpty()) {
            problem.setFenetresRepas(referenceDataService.fenetresRepas());
        }
        if (problem.getConstraintsDesactivees() == null
                || problem.getConstraintsDesactivees().isEmpty()) {
            problem.setConstraintsDesactivees(referenceDataService.getEtatsContraintes().entrySet().stream()
                    .map(etat -> new ConstraintToggle(etat.getKey(), etat.getValue()))
                    .toList());
        }
        if (problem.getQuotasTypologies() == null
                || problem.getQuotasTypologies().isEmpty()) {
            // Only the typologies that carry a cap: the rule joins on them, so
            // an edition capping nothing hands the solver an empty list.
            problem.setQuotasTypologies(referenceDataService.listTypologies().stream()
                    .filter(typologie -> typologie.maxCreneauxParAnimateur() != null)
                    .map(typologie -> new QuotaTypologie(typologie.id(), typologie.maxCreneauxParAnimateur()))
                    .toList());
        }
        // The published plan is the server's knowledge, never the caller's: a
        // planning posted by a client cannot decide what people were told.
        problem.setAffectationsPubliees(affectationsPubliees(problem.getPostes()));
        // Server-side, like the weights below: always overwritten so a caller
        // cannot loosen a quality threshold by sending its own. Read from the
        // edition since issue #591 — the deployment's configuration is what an
        // edition that never opened the screen falls back to, and the service
        // is where the two meet.
        problem.setParametresQualite(List.of(referenceDataService.getParametresQualite()));
        // Never sent by a caller (the field is @JsonIgnore-d on PlanningEvenement),
        // so this always overwrites the ConstraintWeightOverrides.none() default.
        problem.setPonderationsContraintes(configuration.constraintWeightOverrides(problem.getPonderationsScenario()));
    }

    /**
     * The seats of the last published plan, as facts of {@code stabiliteDuPlanPublie};
     * empty when nothing was published, or when the snapshot service is not
     * wired (a plain-Java harness). A seat the publication left empty carries
     * nobody to keep and is skipped.
     */
    List<AffectationPubliee> affectationsPubliees(List<PosteAffectation> postes) {
        PlanSnapshotService snapshotService = snapshots;
        if (snapshotService == null) {
            return List.of();
        }
        PlanSnapshotService.SnapshotDetail publication = snapshotService.loadLastPublication();
        if (publication == null) {
            return List.of();
        }
        return factsPublies(publication.affectations(), postes);
    }

    /**
     * The published seats as facts, <b>without duplicates</b>.
     *
     * <p>A fact says « this line had this person », a stand × vacation × person
     * triple, which several seats can share: a créneau cut into segments gives
     * one seat per segment, and the same person legitimately holds two of them
     * (a stand open 10 h-18 h with a meal-cover shift, say) — and a plan may
     * also have been published with a double booking on one line, which is a
     * hard violation the rule has no business repeating. Timefold indexes
     * problem facts by equality and refuses an equal one twice ("The fact …
     * was already inserted"), so the list must be a set. The constraint only
     * ever asks whether such a line exists, so collapsing the copies changes
     * no score.</p>
     */
    static List<AffectationPubliee> factsPublies(
            List<PlanSnapshotService.AffectationSnapshot> affectations, List<PosteAffectation> postes) {
        // Only for the legacy fallback below; the grid is reachable from the
        // seats being solved, which is the only place the problem carries it.
        Map<String, Creneau> parId = new HashMap<>();
        for (PosteAffectation poste : postes == null ? List.<PosteAffectation>of() : postes) {
            Creneau creneau = poste.getCreneau();
            if (creneau != null && creneau.getId() != null) {
                parId.put(String.valueOf(creneau.getId()), creneau);
            }
        }
        return affectations.stream()
                .filter(affectation -> affectation.animateurId() != null && affectation.standId() != null)
                .map(affectation -> fact(affectation, parId))
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    /**
     * One published seat as a fact, or {@code null} when the vacation it named
     * cannot be described.
     *
     * <p>The day and the hours come from the snapshot itself (issue #576), so
     * a vacation whose créneau has since been deleted and recreated still
     * matches the line that carries it today (issue #578). A snapshot captured
     * before those fields were stored falls back on the créneau its id names in
     * the referential being solved — which is exactly as far as the previous,
     * id-based matching ever reached: the fact is dropped when that créneau is
     * gone, as the join found nothing then either.</p>
     */
    private static AffectationPubliee fact(
            PlanSnapshotService.AffectationSnapshot affectation, Map<String, Creneau> parId) {
        LocalDate date = affectation.date() == null ? null : LocalDate.parse(affectation.date());
        LocalTime debut = heure(affectation.heureDebut());
        LocalTime fin = heure(affectation.heureFin());
        if (date == null || debut == null || fin == null) {
            Creneau creneau = affectation.creneauId() == null ? null : parId.get(affectation.creneauId());
            if (creneau == null) {
                return null;
            }
            date = creneau.getDate();
            debut = creneau.getHeureDebut();
            fin = creneau.getHeureFin();
        }
        if (date == null) {
            return null;
        }
        return new AffectationPubliee(affectation.standId(), date, debut, fin, affectation.animateurId());
    }

    private static LocalTime heure(String texte) {
        return texte == null ? null : LocalTime.parse(texte);
    }
}
