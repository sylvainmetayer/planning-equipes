package dev.sylvain.planning.service;

import java.time.LocalTime;
import java.util.List;

import dev.sylvain.planning.domain.PlanningEvenement;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The plan the animateurs were sent (issue #245), as opposed to the plan being
 * worked on.
 *
 * <p>It is not another way of storing a plan: it is the last snapshot marked
 * published, resolved against today's referential exactly like the persisted
 * plan is. The espace animateur reads from here, so what somebody sees is what
 * somebody sent them — a swap validated this morning does not appear in their
 * espace before the admin publishes.</p>
 *
 * <p>Before the first publication the plan is <b>empty</b>, and deliberately
 * so: falling back on the working plan would recreate exactly the incoherence
 * this feature removes — an espace showing a version nobody announced. The
 * espace says so in as many words instead of showing a planning that is not
 * yet a promise.</p>
 */
@ApplicationScoped
public class PlanPublieService {

    @Inject
    PlanSnapshotService snapshotService;

    @Inject
    PlanningPersistenceService persistenceService;

    /** The last published snapshot's metadata, {@code null} when nothing was ever published. */
    public PlanSnapshotService.SnapshotMeta lastPublication() {
        return snapshotService.lastPublication();
    }

    /** True while this edition has never published anything — the state every edition starts in. */
    public boolean jamaisPublie() {
        return lastPublication() == null;
    }

    /**
     * The published plan, ready to read. Carries the referential (animateurs,
     * stands, créneaux) of today and the seats of the publication: an empty
     * list of postes when nothing has been published, which every read below
     * treats as « rien à montrer », never as « aucune vacation attribuée ».
     */
    public PlanningEvenement planPublie() {
        PlanSnapshotService.SnapshotDetail detail = snapshotService.loadLastPublication();
        if (detail == null) {
            return persistenceService.assemblerPlanning(List.of());
        }
        return persistenceService.assemblerPlanning(detail.affectations().stream()
                .map(PlanPublieService::siege)
                .toList());
    }

    private static PlanningPersistenceService.Siege siege(PlanSnapshotService.AffectationSnapshot affectation) {
        return new PlanningPersistenceService.Siege(
                affectation.posteId(),
                affectation.standId(),
                Long.parseLong(affectation.creneauId()),
                affectation.animateurId(),
                heure(affectation.heureDebutEffective()),
                heure(affectation.heureFinEffective()));
    }

    private static LocalTime heure(String texte) {
        return texte == null ? null : LocalTime.parse(texte);
    }
}
