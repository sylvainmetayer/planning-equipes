package dev.sylvain.planning.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory marker of when reference data (stands, animateurs, créneaux,
 * constraint toggles, ...) was last mutated, so the UI can hint that the
 * persisted planning may be stale even when the active groupe de créneaux
 * hasn't changed. Deliberately not persisted to the database: unlike
 * {@code planning_resolution}, nothing depends on this being correct across a
 * restart, and it is only meant as a soft, best-effort hint.
 *
 * <p>Tracked per {@code edition}: editing 2026 must not make 2025's persisted
 * planning look stale.</p>
 */
@ApplicationScoped
public class ReferenceDataChangeTracker {

    @Inject
    EditionContext editionContext;

    private final Map<String, Instant> lastModifiedByEdition = new ConcurrentHashMap<>();

    public void markModified() {
        lastModifiedByEdition.put(editionId(), Instant.now());
    }

    public Instant lastModifiedAt() {
        return lastModifiedByEdition.get(editionId());
    }

    private String editionId() {
        return editionContext.editionIdCourant();
    }
}
