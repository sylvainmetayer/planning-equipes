package dev.sylvain.planning.service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * In-memory marker of when reference data (stands, animateurs, créneaux,
 * constraint toggles, ...) was last mutated, so the UI can hint that the
 * persisted planning may be stale even when the active groupe de créneaux
 * hasn't changed. Deliberately not persisted to the database: unlike
 * {@code planning_resolution}, nothing depends on this being correct across a
 * restart, and it is only meant as a soft, best-effort hint.
 *
 * <p>Tracked per {@code groupe}: editing 2026 must not make 2025's persisted
 * planning look stale.</p>
 */
@ApplicationScoped
public class ReferenceDataChangeTracker {

    @Inject
    GroupeContext groupeContext;

    private final Map<String, Instant> lastModifiedByGroupe = new ConcurrentHashMap<>();

    public void markModified() {
        lastModifiedByGroupe.put(groupeId(), Instant.now());
    }

    public Instant lastModifiedAt() {
        return lastModifiedByGroupe.get(groupeId());
    }

    /**
     * Null-guarded like {@code ReferenceDataService}'s repository: the plain
     * (non-CDI) tests build this tracker with {@code new}, so the context is
     * not injected and every mark lands under one key.
     */
    private String groupeId() {
        return groupeContext == null ? GroupeRepository.GROUPE_DEFAUT_ID : groupeContext.groupeIdCourant();
    }
}
