package dev.sylvain.planning.service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import ai.timefold.solver.core.api.solver.Solver;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.service.notification.Notification;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

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

    private static final Logger LOG = Logger.getLogger(SolvePipeline.class);

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

    @Inject
    PlanPublieService planPublieService;

    @Inject
    PublicationDiffService diffService;

    /**
     * What a solve produced.
     *
     * @param probleme     the object it started from, returned as is — an
     *                     incremental replanning needs to read it back afterwards
     *                     (frozen scope, previous assignments)
     * @param planning     the solved plan, already persisted
     * @param diagnostic   its score and its violations, already recorded
     * @param previousPlan the plan it replaced and whether that was a step
     *                     back (issue #274); {@code null} when there was none
     */
    public record Resolution<P>(P probleme, PlanningEvenement planning,
            PlanningService.PlanningDiagnostic diagnostic, PreviousPlan previousPlan,
            ImpactPublication impactPublication) {
    }

    /**
     * How many people would have to be told (issue « stabilité ») if this plan
     * were published now: those whose <b>seats differ</b> from the last
     * published plan. {@code null} when nothing was ever published — there is
     * nobody to compare against.
     *
     * <p>Not the same number as the publication screen's {@code nombreConcernes},
     * and deliberately: that one also counts recipients whose schedule did not
     * move but who have an échange decision or a pending request to be told
     * about. This one answers « qui verrait son emploi du temps changer », the
     * question the solve's own recap asks, so it is the smaller of the two.</p>
     *
     * @param publieLe when the plan compared against was published
     */
    public record ImpactPublication(int personnes, Instant publieLe) {
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
        PlanSnapshotService.SnapshotMeta replaced = snapshotService.captureBeforeSolve();
        String scoreBefore = scoreOfReplacedPlan(replaced);
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
        return new Resolution<>(probleme, resolu, diagnostic,
                PreviousPlan.of(replaced == null ? null : replaced.id(), scoreBefore, diagnostic.score()),
                impactPublication(resolu));
    }

    /**
     * The same comparison the publication screen shows, run on the plan just
     * solved so the recap can say « N personnes changeraient d'emploi du temps »
     * before anyone decides to publish. Best-effort: a failure here is a
     * missing figure, never a failed solve.
     */
    private ImpactPublication impactPublication(PlanningEvenement resolu) {
        try {
            PlanSnapshotService.SnapshotMeta publication = planPublieService.lastPublication();
            if (publication == null) {
                return null;
            }
            Map<String, PublicationDiffService.Identite> identites = new HashMap<>();
            for (Animateur animateur : resolu.getAnimateurs()) {
                identites.put(animateur.getId(),
                        new PublicationDiffService.Identite(animateur.nomAffiche(), animateur.getEmail()));
            }
            int personnes = diffService.comparer(
                    PublicationDiffService.vacationsByAnimateur(planPublieService.planPublie()),
                    PublicationDiffService.vacationsByAnimateur(resolu),
                    identites, false).size();
            return new ImpactPublication(personnes, publication.publieLe());
        } catch (RuntimeException e) {
            LOG.warn("The publication impact of the solve could not be computed", e);
            return null;
        }
    }

    /**
     * The score of the plan this solve is about to overwrite (issue #274).
     *
     * <p>A snapshot normally carries it already, copied from
     * {@link ConstraintAnalysisStore} at capture time. That store is in-memory,
     * so it is empty after a restart — which is exactly the case that matters
     * here: coming back the next day and re-solving is when an operator is
     * most likely to lose a good plan without noticing. Recomputing it costs
     * one score analysis of the persisted plan, paid once per edition and per
     * restart, in front of a solve that is about to run for minutes.</p>
     *
     * <p>Never fails the solve, for the same reason the snapshot itself does
     * not: an unavailable comparison is a missing line on a screen, not a lost
     * run.</p>
     */
    private String scoreOfReplacedPlan(PlanSnapshotService.SnapshotMeta replaced) {
        if (replaced == null) {
            return null;
        }
        if (replaced.score() != null) {
            return replaced.score();
        }
        try {
            PlanningService.PlanningDiagnostic diagnostic = planningService.diagnosePersistedPlan();
            if (diagnostic == null) {
                return null;
            }
            // Written back so the snapshots screen shows the same score as the
            // recap that sent the user there to restore it.
            snapshotService.recordScore(replaced.id(), diagnostic.score());
            return diagnostic.score();
        } catch (RuntimeException e) {
            return null;
        }
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
