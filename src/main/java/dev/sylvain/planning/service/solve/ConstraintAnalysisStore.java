package dev.sylvain.planning.service.solve;

import dev.sylvain.planning.service.EditionContext;
import dev.sylvain.planning.service.analyse.PlanningDiagnosticService.PlanningDiagnostic;
import dev.sylvain.planning.service.edition.EditionRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jboss.logging.Logger;

/**
 * Keeps the latest score analysis — written by every solve, and re-derivable
 * from the persisted plan alone — so the constraints screen can show, next to
 * each rule, how it scored. In-memory only: an analysis is a diagnostic, not
 * business data worth persisting.
 *
 * <p>Kept per {@code edition}: an analysis describes one edition's data, so
 * showing 2026's on 2025's constraints screen would be plainly wrong.</p>
 *
 * <p>Because it is in memory, a restart empties it while the plan it describes
 * is still in the database. {@link #latest()} therefore derives the analysis
 * from that plan rather than answering "never analysed" — see its javadoc.</p>
 */
@ApplicationScoped
public class ConstraintAnalysisStore {

    private static final Logger LOG = Logger.getLogger(ConstraintAnalysisStore.class);

    public record StoredAnalysis(Instant analysedAt, PlanningDiagnostic diagnostic) {}

    private final EditionContext editionContext;

    private final PlanningService planningService;

    private final PlanningPersistenceService persistenceService;

    @Inject
    public ConstraintAnalysisStore(
            EditionContext editionContext,
            PlanningService planningService,
            PlanningPersistenceService persistenceService) {
        this.editionContext = editionContext;
        this.planningService = planningService;
        this.persistenceService = persistenceService;
    }

    private final Map<String, StoredAnalysis> latestByEdition = new ConcurrentHashMap<>();

    public void store(PlanningDiagnostic diagnostic) {
        if (diagnostic != null) {
            latestByEdition.put(editionId(), new StoredAnalysis(Instant.now(), diagnostic));
        }
    }

    /**
     * The analysis of the plan in the database: the one the last solve
     * recorded, or — when this process recorded none — one derived from that
     * plan on the spot, and kept.
     *
     * <p><b>Why it derives instead of answering null.</b> This store lives in
     * memory, so a restart empties it; the plan, the referential and the
     * toggles are all still there. Answering "no analysis yet" then makes every
     * reader lie in the same way: the Problèmes screen drops the violations of
     * a plan it is displaying, the Contraintes screen shows a catalogue with no
     * score, an instantané is captured without one. Refreshing the screen did
     * not help either, since the refresh re-read the very same empty map —
     * which is the defect this method removes. Deriving it costs one score
     * calculation of the persisted plan, paid once per edition and per restart,
     * exactly like the one {@code SolvePipeline} already pays to score the plan
     * a solve is about to replace. {@code POST /api/constraints/diagnostic}
     * stays the way to ask for a <em>fresh</em> one.</p>
     *
     * <p>Best-effort, deliberately: a derivation that fails logs and answers
     * null, the "never analysed" state every caller already handles. A read of
     * the analysis must not turn a screen into an error page — the explicit
     * refresh, {@link #refreshFromPersistedPlan()}, is the one that reports.</p>
     */
    public StoredAnalysis latest() {
        StoredAnalysis recorded = latestByEdition.get(editionId());
        return recorded != null ? recorded : deriveFromPersistedPlan();
    }

    /**
     * Re-derives the analysis from the plan currently persisted, and stores it.
     * The one operation that produces an analysis <b>without solving</b>: the
     * stored analysis always describes the persisted plan, so refreshing it can
     * only ever replace it with itself, computed against today's constraints,
     * weights and toggles.
     *
     * <p>Cleared before, never after: a re-analysis that fails leaves "no
     * analysis yet" rather than a previous one the screen would present as
     * describing the plan it shows.</p>
     *
     * @return the fresh analysis, or {@code null} when nothing is persisted to
     *         analyse
     */
    public StoredAnalysis refreshFromPersistedPlan() {
        clear();
        store(planningService.diagnosePersistedPlan());
        // The stored value, not latest(): a plan-less edition would otherwise
        // send that one straight back here to derive what has just been found
        // not to exist.
        return latestByEdition.get(editionId());
    }

    /**
     * Drops the edition's analysis. Called right before a fresh diagnostic is
     * derived (see {@link #refreshFromPersistedPlan}): if that re-analysis
     * fails, the screen honestly shows "no analysis yet" instead of the
     * previous solve's violations against a plan they no longer describe.
     */
    public void clear() {
        latestByEdition.remove(editionId());
    }

    /**
     * The analysis of the persisted plan, computed and kept, or {@code null}
     * when there is no plan to analyse or the analysis could not be produced.
     *
     * <p>The seats are counted before anything is loaded: an edition with no
     * plan is the ordinary state before the first solve, and every reader would
     * otherwise pay a full load of the referential to be told what one
     * {@code COUNT} says.</p>
     */
    private StoredAnalysis deriveFromPersistedPlan() {
        // Both null in the plain (non-CDI) tests, which build this store with
        // new: nothing to derive from, and nothing recorded either.
        if (planningService == null || persistenceService == null) {
            return null;
        }
        try {
            if (persistenceService.countPersistedAssignments() == 0) {
                return null;
            }
            store(planningService.diagnosePersistedPlan());
        } catch (RuntimeException e) {
            LOG.warn("The persisted plan could not be analysed; the screens show no analysis yet", e);
            return null;
        }
        return latestByEdition.get(editionId());
    }

    /**
     * Null-guarded like {@code ReferenceDataService}'s repository: the plain
     * (non-CDI) tests build this store with {@code new}, so the context is not
     * injected and every analysis lands under one key.
     */
    private String editionId() {
        return editionContext == null ? EditionRepository.EDITION_DEFAUT_ID : editionContext.editionIdCourant();
    }
}
