package dev.sylvain.planning.service;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import ai.timefold.solver.core.api.solver.Solver;

import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.service.notification.Notification;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

/**
 * What a solve <b>always</b> does, end to end: snapshot the plan it is about
 * to overwrite, build its problem, solve, persist, diagnose, feed the
 * Contraintes screen, write the KPI row, announce the end.
 *
 * <p>It used to be written four times. Three copies were complete, the fourth —
 * the synchronous solve of {@code POST /api/planning/solve} — stopped after
 * "persist". The consequence was not cosmetic: the Contraintes screen stayed on
 * the analysis of the <i>previous</i> solve, so it displayed violations that no
 * longer described the persisted plan, and no KPI row was written at all. That
 * is what this class exists for: there is no "path that forgets a step" any
 * more, there is one path.</p>
 *
 * <p>Two seams only, because they are the two things that really vary from one
 * caller to the next: <b>how the problem is built</b> (handed over by the
 * request, built from the reference data, or incremental) and <b>what has to
 * hold the solver</b> to be able to stop it — a background job needs that, a
 * synchronous call does not.</p>
 */
@ApplicationScoped
public class SolvePipeline {

    @Inject
    PlanSnapshotService snapshotService;

    @Inject
    PlanningService planningService;

    @Inject
    PlanningPersistenceService persistenceService;

    @Inject
    ConstraintAnalysisStore analysisStore;

    @Inject
    KpiHistoriqueService kpiHistoriqueService;

    @Inject
    ReferenceDataService referenceDataService;

    @Inject
    EditionService editionService;

    @Inject
    Event<Notification> notifications;

    /**
     * What a solve produced.
     *
     * @param probleme    the object it started from, returned as is — an
     *                    incremental replanning needs to read it back afterwards
     *                    (frozen scope, previous assignments)
     * @param planning    the solved plan, already persisted
     * @param diagnostic  its score and its violations, already recorded
     */
    public record Resolution<P>(P probleme, PlanningEvenement planning,
            PlanningService.PlanningDiagnostic diagnostic) {
    }

    /**
     * The common case: the problem is already there, and the edition is the one
     * of the current thread. This is the form {@code POST /api/planning/solve}
     * calls.
     */
    public Resolution<PlanningEvenement> execute(PlanningEvenement probleme, Long secondsLimit) {
        return execute(editionService.editionCourante().getNom(), () -> probleme,
                Function.identity(), secondsLimit, null);
    }

    /** The same, for a background job that must be able to stop its solver. */
    public Resolution<PlanningEvenement> execute(String editionNom, PlanningEvenement probleme,
            Long secondsLimit, Consumer<Solver<PlanningEvenement>> attacheSolveur) {
        return execute(editionNom, () -> probleme, Function.identity(), secondsLimit, attacheSolveur);
    }

    /**
     * The complete form, for a problem that has to be built <b>inside</b> the
     * job: a queued task must solve the edition as it stands when its turn
     * comes, not as it stood when the button was clicked.
     *
     * @param buildProblem called after the previous plan has been
     *                           snapshotted, so never before the net is in place
     * @param planningOf         extracts the planning to solve from the problem,
     *                           when the latter carries more than that
     * @param attacheSolveur     {@code null} when the caller has nothing to stop
     */
    public <P> Resolution<P> execute(String editionNom, Supplier<P> buildProblem,
            Function<P, PlanningEvenement> planningOf, Long secondsLimit,
            Consumer<Solver<PlanningEvenement>> attacheSolveur) {
        // The net of issue #138: the plan about to be overwritten is
        // snapshotted first, so a solve no longer destroys the previous result.
        snapshotService.captureBeforeSolve();
        P probleme = buildProblem.get();
        Instant debutSolve = Instant.now();
        PlanningEvenement resolu = planningService.solve(planningOf.apply(probleme), secondsLimit, attacheSolveur);
        long dureeSolveSecondes = Duration.between(debutSolve, Instant.now()).getSeconds();
        persistenceService.persist(resolu);
        PlanningService.PlanningDiagnostic diagnostic = planningService.diagnose(resolu);
        analysisStore.record(diagnostic);
        // KPI history (issue #89): one row per finished solve, carrying the real
        // duration. Deliberately after the analysis — the KPI reads the score it
        // has just recorded — and never in a position to fail the solve.
        kpiHistoriqueService.recordAfterSolve(dureeSolveSecondes);
        announce(editionNom, diagnostic);
        return new Resolution<>(probleme, resolu, diagnostic);
    }

    /**
     * Announces the result when the edition asks for it — the whole point of a
     * long solve started before leaving. It fires a fact rather than a mail:
     * "never costs the user their result" is no longer a {@code try/catch}
     * written here, it is the delivery policy {@code NotificationDispatcher}
     * applies to every notification.
     */
    private void announce(String editionNom, PlanningService.PlanningDiagnostic diagnostic) {
        if (!referenceDataService.getParametresSolveur().mailFinResolution()) {
            return;
        }
        // Feasible in the Timefold sense: no hard constraint violated any more.
        notifications.fire(new Notification.ResolutionTerminee(
                editionNom, diagnostic.score(), diagnostic.hardScore() >= 0));
    }
}
