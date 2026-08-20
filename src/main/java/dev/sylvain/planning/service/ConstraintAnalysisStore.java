package dev.sylvain.planning.service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import dev.sylvain.planning.service.PlanningService.PlanningDiagnostic;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Keeps the latest score analysis produced by {@code /api/solve/analyze} (sync
 * or async) so the constraints screen can show, next to each rule, how it
 * scored on the last run. In-memory only: an analysis is a diagnostic, not
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
     * Drops the edition's analysis. Called when the persisted plan is
     * rewritten outside of any solve (a snapshot restore) right before the
     * fresh diagnostic is recorded: if that re-analysis fails, the screen
     * honestly shows "no analysis yet" instead of the previous solve's
     * violations against a plan they no longer describe.
     */
    public void effacer() {
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
