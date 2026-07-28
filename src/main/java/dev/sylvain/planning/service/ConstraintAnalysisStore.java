package dev.sylvain.planning.service;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import dev.sylvain.planning.service.PlanningService.PlanningDiagnostic;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Keeps the latest score analysis produced by {@code /api/solve/analyze} (sync
 * or async) so the constraints screen can show, next to each rule, how it
 * scored on the last run. In-memory only: an analysis is a diagnostic, not
 * business data worth persisting.
 */
@ApplicationScoped
public class ConstraintAnalysisStore {

    public record StoredAnalysis(Instant analysedAt, PlanningDiagnostic diagnostic) {
    }

    private final AtomicReference<StoredAnalysis> latest = new AtomicReference<>();

    public void record(PlanningDiagnostic diagnostic) {
        if (diagnostic != null) {
            latest.set(new StoredAnalysis(Instant.now(), diagnostic));
        }
    }

    public StoredAnalysis latest() {
        return latest.get();
    }
}
