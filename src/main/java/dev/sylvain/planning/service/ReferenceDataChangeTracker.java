package dev.sylvain.planning.service;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * In-memory marker of when reference data (stands, animateurs, créneaux,
 * constraint toggles, ...) was last mutated, so the UI can hint that the
 * persisted planning may be stale even when the active groupe de créneaux
 * hasn't changed. Deliberately not persisted to the database: unlike
 * {@code planning_resolution}, nothing depends on this being correct across a
 * restart, and it is only meant as a soft, best-effort hint.
 */
@ApplicationScoped
public class ReferenceDataChangeTracker {

    private final AtomicReference<Instant> lastModifiedAt = new AtomicReference<>();

    public void markModified() {
        lastModifiedAt.set(Instant.now());
    }

    public Instant lastModifiedAt() {
        return lastModifiedAt.get();
    }
}
