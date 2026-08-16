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
 * <p>Kept per {@code groupe}: an analysis describes one edition's data, so
 * showing 2026's on 2025's constraints screen would be plainly wrong.</p>
 */
@ApplicationScoped
public class ConstraintAnalysisStore {

    public record StoredAnalysis(Instant analysedAt, PlanningDiagnostic diagnostic) {
    }

    @Inject
    GroupeContext groupeContext;

    private final Map<String, StoredAnalysis> latestByGroupe = new ConcurrentHashMap<>();

    public void record(PlanningDiagnostic diagnostic) {
        if (diagnostic != null) {
            latestByGroupe.put(groupeId(), new StoredAnalysis(Instant.now(), diagnostic));
        }
    }

    public StoredAnalysis latest() {
        return latestByGroupe.get(groupeId());
    }

    /**
     * Null-guarded like {@code ReferenceDataService}'s repository: the plain
     * (non-CDI) tests build this store with {@code new}, so the context is not
     * injected and every analysis lands under one key.
     */
    private String groupeId() {
        return groupeContext == null ? GroupeRepository.GROUPE_DEFAUT_ID : groupeContext.groupeIdCourant();
    }
}
