package dev.sylvain.planning.service.solve;

import ai.timefold.solver.core.api.solver.Solver;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.service.analyse.KpiHistoriqueService;
import dev.sylvain.planning.service.analyse.PlanningDiagnosticService;
import dev.sylvain.planning.service.analyse.ScoreReading;
import dev.sylvain.planning.service.edition.EditionService;
import dev.sylvain.planning.service.notification.Notification;
import dev.sylvain.planning.service.publication.PlanPublieService;
import dev.sylvain.planning.service.publication.PublicationDiffService;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.validation.ValidationJourneeService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
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

    @Inject
    ValidationJourneeService validationService;

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
     * @param interruption how the run was cut short by the server going
     *                     down; {@code null} for a solve that reached its own
     *                     end — its budget, feasibility, or a cancel
     */
    public record Resolution<P>(
            P probleme,
            PlanningEvenement planning,
            PlanningDiagnosticService.PlanningDiagnostic diagnostic,
            PreviousPlan previousPlan,
            ImpactPublication impactPublication,
            ImpactValidations impactValidations,
            Interruption interruption) {}

    /**
     * A solve the server stopped under — a graceful shutdown, or the CDI
     * container going down for a live reload — rather than one that ended on
     * its own terms.
     *
     * <p>Timefold treats the stop as an ordinary termination and hands back
     * its best solution so far; this records what became of it. That plan
     * replaces the persisted one <b>only when it scores strictly higher</b>:
     * a run cut short after a few seconds is usually far below the plan it
     * would have overwritten, and the persisted plan is what an operator
     * walks away with.</p>
     *
     * @param partialPlanKept whether the best solution so far was persisted
     * @param partialScore    its score
     * @param persistedScore  the score of the plan that was in place,
     *                        {@code null} when there was none or it could not
     *                        be established
     */
    public record Interruption(boolean partialPlanKept, String partialScore, String persistedScore) {}

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
    public record ImpactPublication(int personnes, Instant publieLe) {}

    /**
     * Readings this solve invalidated: days somebody had marked « relu et
     * accepté » and on which a seat has just moved. {@code null} when it
     * withdrew none — an edition nobody reviews, or a solve that moved nothing
     * anybody had read — so the recap says nothing rather than « 0 ».
     *
     * <p>A day also carrying a {@code JOUR} lock keeps its validation and is
     * not counted: the solver could not move anything there, so the reading
     * still describes what is in place.</p>
     */
    public record ImpactValidations(int journees) {}

    /**
     * The common case: the problem is already there, and the edition is the one
     * of the current thread. This is the form {@code POST /api/planning/solve}
     * calls.
     */
    public Resolution<PlanningEvenement> execute(PlanningEvenement probleme, Long secondsLimit) {
        return execute(
                editionService.editionCourante().getNom(),
                () -> probleme,
                Function.identity(),
                secondsLimit,
                null,
                () -> false);
    }

    /** The same, for a background job that must be able to stop its solver. */
    public Resolution<PlanningEvenement> execute(
            String editionNom,
            PlanningEvenement probleme,
            Long secondsLimit,
            Consumer<Solver<PlanningEvenement>> attacheSolveur,
            BooleanSupplier shutdownRequested) {
        return execute(
                editionNom, () -> probleme, Function.identity(), secondsLimit, attacheSolveur, shutdownRequested);
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
     * @param shutdownRequested  whether the server is going down — read once
     *                           the solver has returned, to tell a run it cut
     *                           short from one that reached its own end
     */
    public <P> Resolution<P> execute(
            String editionNom,
            Supplier<P> buildProblem,
            Function<P, PlanningEvenement> planningOf,
            Long secondsLimit,
            Consumer<Solver<PlanningEvenement>> attacheSolveur,
            BooleanSupplier shutdownRequested) {
        // The net of issue #138: the plan about to be overwritten is
        // snapshotted first, so a solve no longer destroys the previous result.
        PlanSnapshotService.SnapshotMeta replaced = snapshotService.captureBeforeSolve();
        String scoreBefore = scoreOfReplacedPlan(replaced);
        PlanningDiagnosticService.PlanningDiagnostic diagnosticBefore = diagnosticOfReplacedPlan(replaced, scoreBefore);
        P probleme = buildProblem.get();
        Instant debutSolve = Instant.now();
        PlanningEvenement resolu = planningService.solve(planningOf.apply(probleme), secondsLimit, attacheSolveur);
        long dureeSolveSecondes = Duration.between(debutSolve, Instant.now()).getSeconds();
        // Timefold also stops when its thread is interrupted — a pool being
        // shut down does that — and returns as if the budget were spent.
        // Either signal means the run was cut short by the server, not by the
        // problem, and its plan must not overwrite a better one.
        if (shutdownRequested.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            return interrupted(probleme, resolu, replaced, scoreBefore, dureeSolveSecondes);
        }
        // Read before the persist overwrites it: what the plan in place held is
        // the only thing the days that moved can be compared against.
        Map<String, List<String>> avant = assignmentsBeforePersist();
        persistenceService.persistAfterSolve(resolu, replaced == null ? null : replaced.id());
        PlanningDiagnosticService.PlanningDiagnostic diagnostic = planningService.diagnose(resolu);
        analysisStore.record(diagnostic);
        // KPI history (issue #89): one row per finished solve, carrying the real
        // duration. Deliberately after the analysis — the KPI reads the score it
        // has just recorded — and never in a position to fail the solve.
        kpiHistoriqueService.recordAfterSolve(dureeSolveSecondes);
        announce(editionNom, diagnostic);
        // The comparison goes to the Solveur page alone: the stored analysis
        // describes the persisted plan, and outlives the moment « the plan
        // before » means anything.
        PlanningDiagnosticService.PlanningDiagnostic withComparison = diagnosticBefore == null
                ? diagnostic
                : diagnostic.withReading(ScoreReading.withComparison(diagnostic, diagnosticBefore));
        return new Resolution<>(
                probleme,
                resolu,
                withComparison,
                PreviousPlan.of(replaced == null ? null : replaced.id(), scoreBefore, diagnostic.score()),
                impactPublication(resolu),
                impactValidations(avant, resolu),
                null);
    }

    /**
     * The end of a run the server stopped under. Its best plan so far is
     * persisted only when it beats the plan in place — or when there is none
     * to lose — and nothing else a finished solve does (KPI row, end-of-solve
     * mail, publication impact) is done for a run that did not finish.
     */
    private <P> Resolution<P> interrupted(
            P probleme,
            PlanningEvenement resolu,
            PlanSnapshotService.SnapshotMeta replaced,
            String scoreBefore,
            long dureeSolveSecondes) {
        // Cleared before touching the database: a connection pool refuses an
        // interrupted thread, and a plan worth keeping must be writable.
        Thread.interrupted();
        PlanningDiagnosticService.PlanningDiagnostic diagnostic = planningService.diagnose(resolu);
        boolean kept = keepsPartialPlan(scoreBefore, diagnostic.score());
        Map<String, List<String>> avant = kept ? assignmentsBeforePersist() : null;
        if (kept) {
            persistenceService.persistAfterSolve(resolu, replaced == null ? null : replaced.id());
            analysisStore.record(diagnostic);
            kpiHistoriqueService.recordAfterSolve(dureeSolveSecondes);
            LOG.infof(
                    "Solve stopped by the server after %d s: its plan (%s) replaces the persisted one (%s)",
                    dureeSolveSecondes, diagnostic.score(), scoreBefore);
        } else {
            LOG.infof(
                    "Solve stopped by the server after %d s: its plan (%s) is discarded, the persisted one (%s) stands",
                    dureeSolveSecondes, diagnostic.score(), scoreBefore);
        }
        return new Resolution<>(
                probleme,
                resolu,
                diagnostic,
                PreviousPlan.of(replaced == null ? null : replaced.id(), scoreBefore, diagnostic.score()),
                null,
                // A kept partial plan replaced the persisted one just the same:
                // the readings of the days it moved are as stale as after a
                // solve that finished, and nothing else would ever withdraw them.
                impactValidations(avant, resolu),
                new Interruption(kept, diagnostic.score(), scoreBefore));
    }

    /**
     * Whether the plan of an interrupted run replaces the one in place.
     *
     * <p>Asks the database rather than trusting the snapshot: a capture that
     * failed leaves {@code scoreBefore} null while a plan is very much still
     * there, and that is precisely the case this guard exists for. With no
     * plan stored there is nothing to lose, so the partial one is kept;
     * otherwise it has to score strictly higher. Anything unreadable — the
     * count, the score — answers no: the plan an operator already has is
     * worth more than the one a live reload cut short.</p>
     */
    private boolean keepsPartialPlan(String scoreBefore, String scoreAfter) {
        try {
            if (persistenceService.countPersistedAssignments() == 0) {
                return true;
            }
        } catch (RuntimeException e) {
            LOG.warn("The persisted plan could not be counted; the partial plan is discarded", e);
            return false;
        }
        return PreviousPlan.isImproved(scoreBefore != null ? scoreBefore : scoreOfPersistedPlan(), scoreAfter);
    }

    /** The score of the plan in place, {@code null} when it cannot be established. */
    private String scoreOfPersistedPlan() {
        try {
            PlanningDiagnosticService.PlanningDiagnostic diagnostic = planningService.diagnosePersistedPlan();
            return diagnostic == null ? null : diagnostic.score();
        } catch (RuntimeException e) {
            LOG.warn("The persisted plan's score could not be established", e);
            return null;
        }
    }

    /**
     * The plan in place, seat by cell — the before-image the days that moved
     * are read from, or {@code null} when there is nothing to compare.
     *
     * <p>{@code null} on an edition carrying no reading at all: the image is
     * the whole persisted plan seat by seat, and an edition nobody reviews must
     * not pay for it on every solve. Distinct from an <b>empty</b> image, which
     * says the plan held nothing — every day then genuinely moved, and the
     * readings taken before the first solve are genuinely stale.</p>
     *
     * <p>Best-effort: a failure here costs the recap a figure and leaves the
     * readings alone, it never fails a solve.</p>
     */
    private Map<String, List<String>> assignmentsBeforePersist() {
        try {
            if (!validationService.hasValidations()) {
                return null;
            }
            return persistenceService.loadAnimateursByStandCreneau();
        } catch (RuntimeException e) {
            LOG.warn("The persisted plan could not be read back; no reading is withdrawn", e);
            return null;
        }
    }

    /**
     * Withdraws the « relu et accepté » of every day this solve moved a seat
     * on, and says how many were withdrawn — « 2 journées validées ont bougé ».
     *
     * <p>Best-effort like the publication impact: an edition nobody reviews
     * must not see a solve fail on a figure it does not read.</p>
     */
    private ImpactValidations impactValidations(Map<String, List<String>> avant, PlanningEvenement resolu) {
        if (avant == null) {
            return null;
        }
        try {
            int journees = validationService.withdrawMovedDays(ReplanificationDiff.joursModifies(avant, resolu));
            return journees == 0 ? null : new ImpactValidations(journees);
        } catch (RuntimeException e) {
            LOG.warn("The readings of the days this solve moved could not be withdrawn", e);
            return null;
        }
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
                identites.put(
                        animateur.getId(),
                        new PublicationDiffService.Identite(animateur.nomAffiche(), animateur.getEmail()));
            }
            int personnes = diffService
                    .comparer(
                            PublicationDiffService.vacationsByAnimateur(planPublieService.planPublie()),
                            PublicationDiffService.vacationsByAnimateur(resolu),
                            identites,
                            false)
                    .size();
            return new ImpactPublication(personnes, publication.publieLe());
        } catch (RuntimeException e) {
            LOG.warn("The publication impact of the solve could not be computed", e);
            return null;
        }
    }

    /**
     * The diagnostic of the plan a solve is about to replace, for the
     * comparison sentence of the reading — the stored analysis, and only when
     * it scores what that plan scored: an analysis recorded before a restore
     * or a hand move describes another plan, and comparing against it would
     * state a change nobody made. {@code null} when that cannot be told.
     */
    private PlanningDiagnosticService.PlanningDiagnostic diagnosticOfReplacedPlan(
            PlanSnapshotService.SnapshotMeta replaced, String scoreBefore) {
        if (replaced == null || scoreBefore == null) {
            return null;
        }
        try {
            ConstraintAnalysisStore.StoredAnalysis stored = analysisStore.latest();
            return stored != null && scoreBefore.equals(stored.diagnostic().score()) ? stored.diagnostic() : null;
        } catch (RuntimeException e) {
            LOG.warn("The replaced plan's diagnostic could not be read; the reading carries no comparison", e);
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
            PlanningDiagnosticService.PlanningDiagnostic diagnostic = planningService.diagnosePersistedPlan();
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
    private void announce(String editionNom, PlanningDiagnosticService.PlanningDiagnostic diagnostic) {
        if (!referenceDataService.getParametresSolveur().mailFinResolution()) {
            return;
        }
        // Feasible in the Timefold sense: no hard constraint violated any more.
        notifications.fire(
                new Notification.ResolutionTerminee(editionNom, diagnostic.score(), diagnostic.hardScore() >= 0));
    }
}
