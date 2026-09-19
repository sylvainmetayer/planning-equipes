package dev.sylvain.planning.service.export;

import dev.sylvain.planning.service.edition.EtiquetteEdition;
import dev.sylvain.planning.service.edition.EtiquetteEditionService;
import dev.sylvain.planning.service.solve.PlanSnapshotService;
import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;

/**
 * Reads the provenance from the édition the request works in, and from
 * whichever plan the document carries: the {@code planning_resolution} row for
 * the working plan, the last published snapshot for the published one.
 *
 * <p>No lookup is allowed to sink an export: an édition that cannot be
 * resolved, one never solved, or one never published leaves the corresponding
 * half of the footer unsaid rather than raising.</p>
 */
@ApplicationScoped
public class PersistedExportProvenance implements ExportProvenance {

    private final EtiquetteEditionService etiquettes;
    private final PlanningPersistenceService persistence;
    private final PlanSnapshotService snapshots;

    @Inject
    public PersistedExportProvenance(
            EtiquetteEditionService etiquettes, PlanningPersistenceService persistence, PlanSnapshotService snapshots) {
        this.etiquettes = etiquettes;
        this.persistence = persistence;
        this.snapshots = snapshots;
    }

    @Override
    public Provenance courante() {
        return new Provenance(etiquette(), solvedAt(), Nature.RESOLUTION);
    }

    @Override
    public Provenance publiee() {
        return new Provenance(etiquette(), publishedAt(), Nature.PUBLICATION);
    }

    private EtiquetteEdition etiquette() {
        return etiquettes.courante();
    }

    private Instant solvedAt() {
        PlanningPersistenceService.PlanningResolution resolution = persistence.loadResolution();
        return resolution == null ? null : resolution.resoluLe();
    }

    private Instant publishedAt() {
        PlanSnapshotService.SnapshotMeta publication = snapshots.lastPublication();
        return publication == null ? null : publication.publieLe();
    }
}
