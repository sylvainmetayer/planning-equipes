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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
        return publicationInForce()
                .map(PublishedPlan::plan)
                .orElseGet(() -> persistenceService.assemblerPlanning(List.of()));
    }

    /**
     * The publication in force and the plan it holds, read from <b>one</b>
     * load of the snapshot — where {@link #lastPublication()} followed by
     * {@link #planPublie()} could date one publication and read the next, were
     * an admin to publish between the two. Empty when nothing has ever been
     * published: unlike {@link #planPublie()}, a caller that must say which
     * plan it read has nothing to read then, not an empty plan.
     */
    public Optional<PublishedPlan> publicationInForce() {
        PlanSnapshotService.SnapshotDetail detail = snapshotService.loadLastPublication();
        if (detail == null) {
            return Optional.empty();
        }
        return Optional.of(new PublishedPlan(
                detail.meta(),
                persistenceService.assemblerPlanning(detail.affectations().stream()
                        .map(PlanSnapshotService::seat)
                        .toList())));
    }

    /**
     * @param publication the published snapshot's metadata — its {@code publieLe} dates the plan
     * @param plan        the seats it holds, resolved as {@link #planPublie()} resolves them
     */
    public record PublishedPlan(PlanSnapshotService.SnapshotMeta publication, PlanningEvenement plan) {}

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
     * A reader of several snapshots' plans for one computation: each snapshot
     * is read and assembled once however often it is asked for, and all of
     * them against <b>one</b> read of the referential — where {@link
     * #plan(long)} re-reads it for each. The referential is read on the
     * first plan asked for, not before: a computation that needs none pays
     * nothing.
     *
     * <p>Not {@link #vacationsBySnapshot}: those vacations are by person, and
     * leave out the seats nobody held — the chairs a reader counting seats
     * needs.</p>
     */
    public SnapshotPlans snapshotPlans() {
        return new SnapshotPlans();
    }

    /** See {@link #snapshotPlans()}. Not thread-safe: one per computation. */
    public final class SnapshotPlans {

        private final Map<Long, PlanningEvenement> plans = new HashMap<>();

        /** The snapshots asked for that no longer exist: read once, like the others. */
        private final Set<Long> missing = new HashSet<>();

        private PlanningPersistenceService.PlanAssembler assembler;

        private SnapshotPlans() {}

        /** The plan {@code snapshotId} holds, empty when it no longer exists — as {@link #plan(long)}. */
        public Optional<PlanningEvenement> plan(long snapshotId) {
            if (missing.contains(snapshotId)) {
                return Optional.empty();
            }
            PlanningEvenement read = plans.get(snapshotId);
            if (read != null) {
                return Optional.of(read);
            }
            PlanSnapshotService.SnapshotDetail detail = snapshotService.load(snapshotId);
            if (detail == null) {
                missing.add(snapshotId);
                return Optional.empty();
            }
            if (assembler == null) {
                assembler = persistenceService.getAssembler();
            }
            read = assembler.assemble(detail.affectations().stream()
                    .map(PlanSnapshotService::seat)
                    .toList());
            plans.put(snapshotId, read);
            return Optional.of(read);
        }
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
            parSnapshot.put(
                    id, detail == null ? Optional.empty() : Optional.of(vacations(detail.affectations(), nomsDeStand)));
        }
        return parSnapshot;
    }

    /**
     * The vacations of one snapshot, by animateur, read as {@link
     * PublicationDiffService#vacationsByAnimateur} reads the working plan: a
     * seat split on the day (ADR 0066) whose rest its own holder kept is one
     * vacation, from the origin's start to the rest's end. Read as two here and
     * as one there, the same seat was an écart at every publication, and its
     * holder never left the recipients.
     */
    static Map<String, List<Vacation>> vacations(
            List<PlanSnapshotService.AffectationSnapshot> affectations, Map<String, String> nomsDeStand) {
        Map<String, PlanSnapshotService.AffectationSnapshot> byId = new HashMap<>();
        Map<String, PlanSnapshotService.AffectationSnapshot> suites = new HashMap<>();
        for (PlanSnapshotService.AffectationSnapshot affectation : affectations) {
            byId.putIfAbsent(affectation.posteId(), affectation);
            if (affectation.suiteDe() != null) {
                suites.putIfAbsent(affectation.suiteDe(), affectation);
            }
        }
        Map<String, List<Vacation>> parAnimateur = new LinkedHashMap<>();
        for (PlanSnapshotService.AffectationSnapshot affectation : affectations) {
            if (keptByItsHolder(affectation, byId)) {
                continue;
            }
            PlanSnapshotService.AffectationSnapshot derniere = affectation;
            for (PlanSnapshotService.AffectationSnapshot suite = suites.get(derniere.posteId());
                    suite != null && keptByItsHolder(suite, byId);
                    suite = suites.get(derniere.posteId())) {
                derniere = suite;
            }
            Vacation vacation = vacation(affectation, derniere, nomsDeStand);
            if (vacation != null) {
                parAnimateur
                        .computeIfAbsent(affectation.animateurId(), unused -> new ArrayList<>())
                        .add(vacation);
            }
        }
        return parAnimateur;
    }

    /** Whether this row is the rest of a seat the same person held — part of the origin's vacation, not one more. */
    private static boolean keptByItsHolder(
            PlanSnapshotService.AffectationSnapshot affectation,
            Map<String, PlanSnapshotService.AffectationSnapshot> byId) {
        PlanSnapshotService.AffectationSnapshot origine =
                affectation.suiteDe() == null ? null : byId.get(affectation.suiteDe());
        return origine != null
                && affectation.animateurId() != null
                && affectation.animateurId().equals(origine.animateurId());
    }

    /**
     * The vacation a snapshot row describes, ending where {@code derniere} —
     * the last part its holder kept, the row itself otherwise — ends;
     * {@code null} for an empty seat or a row missing its day or hours.
     */
    private static Vacation vacation(
            PlanSnapshotService.AffectationSnapshot affectation,
            PlanSnapshotService.AffectationSnapshot derniere,
            Map<String, String> nomsDeStand) {
        if (affectation.animateurId() == null || affectation.standId() == null || affectation.date() == null) {
            return null;
        }
        LocalTime debut = heure(affectation.heureDebutEffective(), affectation.heureDebut());
        LocalTime fin = heure(derniere.heureFinEffective(), derniere.heureFin());
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
