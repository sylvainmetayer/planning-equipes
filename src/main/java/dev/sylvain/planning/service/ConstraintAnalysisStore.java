package dev.sylvain.planning.service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import dev.sylvain.planning.service.PlanningService.PlanningDiagnostic;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Keeps the latest score analysis — written by every solve, and re-derivable
 * from the persisted plan alone — so the constraints screen can show, next to
 * each rule, how it scored. In-memory only: an analysis is a diagnostic, not
 * business data worth persisting.
 *
 * <p>Kept per {@code edition}: an analysis describes one edition's data, so
 * showing 2026's on 2025's constraints screen would be plainly wrong.</p>
 */
@ApplicationScoped
public class ConstraintAnalysisStore {

    public record StoredAnalysis(Instant analysedAt, PlanningDiagnostic diagnostic) {
    }

    @Inject
    EditionContext editionContext;

    @Inject
    PlanningService planningService;

    private final Map<String, StoredAnalysis> latestByEdition = new ConcurrentHashMap<>();

    public void record(PlanningDiagnostic diagnostic) {
        if (diagnostic != null) {
            latestByEdition.put(editionId(), new StoredAnalysis(Instant.now(), diagnostic));
        }
    }

    public StoredAnalysis latest() {
        return latestByEdition.get(editionId());
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
        record(planningService.diagnosePersistedPlan());
        return latest();
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
     * Null-guarded like {@code ReferenceDataService}'s repository: the plain
     * (non-CDI) tests build this store with {@code new}, so the context is not
     * injected and every analysis lands under one key.
     */
    private String editionId() {
        return editionContext == null ? EditionRepository.EDITION_DEFAUT_ID : editionContext.editionIdCourant();
    }
}
