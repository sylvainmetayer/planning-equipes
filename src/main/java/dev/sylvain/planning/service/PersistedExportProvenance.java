package dev.sylvain.planning.service;

import java.time.Instant;

import dev.sylvain.planning.domain.Edition;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Reads the provenance from the édition the request works in and from the
 * {@code planning_resolution} row that édition owns.
 *
 * <p>Neither lookup is allowed to sink an export: an édition that cannot be
 * resolved, or one never solved, leaves the corresponding half of the footer
 * unsaid rather than raising.</p>
 */
@ApplicationScoped
public class PersistedExportProvenance implements ExportProvenance {

    private final EditionService editions;
    private final PlanningPersistenceService persistence;

    @Inject
    public PersistedExportProvenance(EditionService editions, PlanningPersistenceService persistence) {
        this.editions = editions;
        this.persistence = persistence;
    }

    @Override
    public Provenance courante() {
        return new Provenance(editionNom(), solvedAt());
    }

    private String editionNom() {
        try {
            Edition edition = editions.editionCourante();
            return edition == null ? null : edition.getNom();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private Instant solvedAt() {
        PlanningPersistenceService.PlanningResolution resolution = persistence.loadResolution();
        return resolution == null ? null : resolution.resoluLe();
    }
}
