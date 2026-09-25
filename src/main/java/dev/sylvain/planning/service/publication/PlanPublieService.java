package dev.sylvain.planning.service.publication;

import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.publication.PublicationDiffService.Vacation;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.solve.PlanSnapshotService;
import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
 * <p>Resolved against today's referential, but no longer <b>dependent</b> on it
 * (issue #576): a seat whose créneau has since been deleted keeps the day and
 * the hours the snapshot recorded for it. A promise that was made does not stop
 * having been made because the grid moved — it stays in the espace, and in the
 * comparison, until a publication announces its retrait.</p>
 *
 * <p>Before the first publication the plan is <b>empty</b>, and deliberately
 * so: falling back on the working plan would recreate exactly the incoherence
 * this feature removes — an espace showing a version nobody announced. The
 * espace says so in as many words instead of showing a planning that is not
 * yet a promise.</p>
 */
@ApplicationScoped
public class PlanPublieService {

    private final PlanSnapshotService snapshotService;

    private final PlanningPersistenceService persistenceService;

    private final ReferenceDataService referenceDataService;

    @Inject
    public PlanPublieService(
            PlanSnapshotService snapshotService,
            PlanningPersistenceService persistenceService,
            ReferenceDataService referenceDataService) {
        this.snapshotService = snapshotService;
        this.persistenceService = persistenceService;
        this.referenceDataService = referenceDataService;
    }

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
        return persistenceService.assemblerPlanning(
                detail.affectations().stream().map(PlanSnapshotService::seat).toList());
    }

    /**
     * The plan one given snapshot holds, read exactly as {@link #planPublie()}
     * reads the last published one.
     *
     * <p>What the publication diff needs since somebody can be deferred
     * (issue #503): « ce qu'on a annoncé » is then no longer one reference for
     * everybody but one per person, and the people carried over from a
     * previous publication are read against the snapshot they really
     * received.</p>
     *
     * <p>Empty when the snapshot no longer exists — deleted, or purged by the
     * retention sweep. Not an empty <b>plan</b>, which would claim that person
     * was told they had nothing: the caller reads the absence as « jamais
     * prévenu », the only truthful reading once there is nothing left to
     * compare against.</p>
     */
    public Optional<PlanningEvenement> plan(long snapshotId) {
        PlanSnapshotService.SnapshotDetail detail = snapshotService.load(snapshotId);
        if (detail == null) {
            return Optional.empty();
        }
        return Optional.of(persistenceService.assemblerPlanning(
                detail.affectations().stream().map(PlanSnapshotService::seat).toList()));
    }

    /**
     * The vacations of several published snapshots at once, keyed by snapshot
     * then by animateur — what the per-person reference of issue #503 reads.
     *
     * <p>Built from the snapshot rows rather than through {@link #plan(long)}:
     * that one assembles a whole {@link PlanningEvenement}, which re-reads the
     * animateurs, the créneaux and the stands and resolves every horaire —
     * once per snapshot. Markers diverge as people are deferred, so the
     * preview was paying that price as many times as there are distinct
     * references. The stand names are read once here, and nothing else of the
     * referential is needed: a vacation is a day, hours and a stand.</p>
     *
     * <p>The day and the hours are the snapshot's own, not today's créneau —
     * which is what « ce qu'on a annoncé » means, and what lets a snapshot
     * outlive the créneaux it names (issue #576).</p>
     *
     * <p>A snapshot that no longer exists maps to {@link Optional#empty()},
     * never to an empty map: the caller reads the absence as « jamais
     * prévenu », where an empty plan would claim the person was told they had
     * nothing.</p>
     */
    public Map<Long, Optional<Map<String, List<Vacation>>>> vacationsBySnapshot(Collection<Long> snapshotIds) {
        Map<Long, Optional<Map<String, List<Vacation>>>> parSnapshot = new LinkedHashMap<>();
        if (snapshotIds.isEmpty()) {
            return parSnapshot;
        }
        Map<String, String> nomsDeStand = new HashMap<>();
        for (Stand stand : referenceDataService.listStands()) {
            nomsDeStand.put(stand.getId(), stand.getNom());
        }
        for (Long id : snapshotIds) {
            if (id == null || parSnapshot.containsKey(id)) {
                continue;
            }
            PlanSnapshotService.SnapshotDetail detail = snapshotService.load(id);
            parSnapshot.put(id, detail == null ? Optional.empty() : Optional.of(vacations(detail, nomsDeStand)));
        }
        return parSnapshot;
    }

    private static Map<String, List<Vacation>> vacations(
            PlanSnapshotService.SnapshotDetail detail, Map<String, String> nomsDeStand) {
        Map<String, List<Vacation>> parAnimateur = new LinkedHashMap<>();
        for (PlanSnapshotService.AffectationSnapshot affectation : detail.affectations()) {
            Vacation vacation = vacation(affectation, nomsDeStand);
            if (vacation != null) {
                parAnimateur
                        .computeIfAbsent(affectation.animateurId(), unused -> new ArrayList<>())
                        .add(vacation);
            }
        }
        return parAnimateur;
    }

    /** The vacation one snapshot row describes, {@code null} for an empty seat or a row missing its day or hours. */
    private static Vacation vacation(
            PlanSnapshotService.AffectationSnapshot affectation, Map<String, String> nomsDeStand) {
        if (affectation.animateurId() == null || affectation.standId() == null || affectation.date() == null) {
            return null;
        }
        LocalTime debut = heure(affectation.heureDebutEffective(), affectation.heureDebut());
        LocalTime fin = heure(affectation.heureFinEffective(), affectation.heureFin());
        if (debut == null || fin == null) {
            return null;
        }
        return new Vacation(
                LocalDate.parse(affectation.date()),
                debut,
                fin,
                affectation.standId(),
                nomsDeStand.getOrDefault(affectation.standId(), affectation.standId()));
    }

    /** The narrowed window when a stand closure recorded one, the vacation's own otherwise. */
    private static LocalTime heure(String effective, String creneau) {
        String lue = effective != null ? effective : creneau;
        return lue == null ? null : LocalTime.parse(lue);
    }
}
