package dev.sylvain.planning.service.analyse;

import dev.sylvain.planning.domain.Edition;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.EditionContext;
import dev.sylvain.planning.service.analyse.RealisedSource.Realised;
import dev.sylvain.planning.service.analyse.RealisedVsPlanned.CellDetail;
import dev.sylvain.planning.service.analyse.RealisedVsPlanned.PreviousEdition;
import dev.sylvain.planning.service.analyse.RealisedVsPlannedAnalyzer.ChosenReference;
import dev.sylvain.planning.service.analyse.RealisedVsPlannedAnalyzer.DayReference;
import dev.sylvain.planning.service.analyse.RealisedVsPlannedAnalyzer.DaySpan;
import dev.sylvain.planning.service.analyse.RealisedVsPlannedAnalyzer.Publication;
import dev.sylvain.planning.service.edition.EditionRepository;
import dev.sylvain.planning.service.espace.JourJClock;
import dev.sylvain.planning.service.journal.JournalActionService;
import dev.sylvain.planning.service.publication.PlanPublieService;
import dev.sylvain.planning.service.publication.PlanPublieService.SnapshotPlans;
import dev.sylvain.planning.service.referentiel.JoursEvenement;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.referentiel.TypologieItem;
import dev.sylvain.planning.service.solve.PlanSnapshotService;
import dev.sylvain.planning.service.solve.PlanningService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * Réalisé vs planifié for the current edition: the elapsed days, each against
 * the publication in force when it started, and the realised read from its
 * {@link RealisedSource}.
 *
 * <p>Computed on demand, and cached once the event is over (ADR 0092): the
 * first report asked after the edition's last timeslot has ended, and only
 * if something was published, writes the measure into {@code kpi_realise},
 * where the next edition reads it. It writes once — an edition that has its
 * rows is left alone, so a later edit does not rewrite it; re-freezing is an
 * operator's SQL {@code DELETE}. The measure itself is {@link RealisedVsPlannedAnalyzer}'s,
 * over {@code ChangementsJourneeService.compareSeats}; this class finds the
 * plans it needs — each reference publication read once per computation, all
 * of them against one read of the referential ({@link SnapshotPlans}) — and
 * the clock.</p>
 *
 * <p>« Elapsed » is a day whose <b>last timeslot has ended</b>, on the clock
 * the past rule reads ({@link JourJClock#dateTime()}): not midnight, which
 * would count a day whose night shift is still being held. The future is not
 * counted either: there is no gap on what has not taken place. A day
 * <b>starts</b> at its first timeslot, as the mode jour J counts it, and the
 * publication in force at that moment is its reference.</p>
 */
@ApplicationScoped
public class RealisedVsPlannedService {

    private static final Logger LOG = Logger.getLogger(RealisedVsPlannedService.class);

    private final RealisedSource source;

    private final PlanSnapshotService snapshotService;

    private final PlanPublieService planPublieService;

    private final ReferenceDataService referenceDataService;

    private final PlanningService planningService;

    private final JourJClock clock;

    private final RealisedHistoryRepository history;

    private final JournalActionService journal;

    private final EditionRepository editionRepository;

    private final EditionContext editionContext;

    @Inject
    public RealisedVsPlannedService(
            RealisedSource source,
            PlanSnapshotService snapshotService,
            PlanPublieService planPublieService,
            ReferenceDataService referenceDataService,
            PlanningService planningService,
            JourJClock clock,
            RealisedHistoryRepository history,
            JournalActionService journal,
            EditionRepository editionRepository,
            EditionContext editionContext) {
        this.source = source;
        this.snapshotService = snapshotService;
        this.planPublieService = planPublieService;
        this.referenceDataService = referenceDataService;
        this.planningService = planningService;
        this.clock = clock;
        this.history = history;
        this.journal = journal;
        this.editionRepository = editionRepository;
        this.editionContext = editionContext;
    }

    /** The report as of now. */
    public RealisedVsPlanned report() {
        return report(clock.dateTime());
    }

    /** The report as of {@code now}, cached into {@code kpi_realise} when the event is over. */
    public RealisedVsPlanned report(LocalDateTime now) {
        RealisedVsPlanned report = compute(now);
        cacheIfOver(report, now);
        return report;
    }

    private RealisedVsPlanned compute(LocalDateTime now) {
        Realised realised = source.read();
        List<Publication> publications = publications();
        List<DayReference> days = dayReferences(now, realised.plan(), publications, planPublieService.snapshotPlans());
        Map<String, String> labels = new HashMap<>();
        for (TypologieItem typologie : referenceDataService.listTypologies()) {
            labels.put(typologie.id(), typologie.label());
        }
        return RealisedVsPlannedAnalyzer.analyse(
                days,
                realised,
                !publications.isEmpty(),
                planningService.pastHorizon() != null,
                now.toLocalDate(),
                labels);
    }

    /**
     * Writes the measure once, once the event is over: not
     * before the last timeslot has ended, not without a publication to measure,
     * and not while the frozen past is off — a realised nobody guarantees must
     * not be written once and kept. A failure is logged, never propagated: the
     * report was computed and is still answered.
     */
    private void cacheIfOver(RealisedVsPlanned report, LocalDateTime now) {
        // A simulated clock (demos, training) must never freeze a measure nobody lived.
        if (clock.mockedDate() != null) {
            return;
        }
        try {
            if (!report.referenceAvailable() || !isOver(now) || history.exists()) {
                return;
            }
            if (!report.frozenPast()) {
                LOG.debug("The realised measure is not cached: the frozen past is switched off (PASSE_FIGE=false)");
                return;
            }
            JoursEvenement jours = JoursEvenement.of(referenceDataService.listCreneaux());
            // ON CONFLICT DO NOTHING: when a concurrent read wins, it is the one that journals.
            if (history.insert(editionName(), jours.first(), jours.last(), report, Instant.now()) > 0) {
                journal.recordSystemAction("REALISE_FIGE", null);
            }
        } catch (RuntimeException e) {
            LOG.error("The realised measure could not be cached; the report is answered all the same", e);
        }
    }

    private String editionName() {
        String id = editionContext.editionIdCourant();
        return editionRepository.find(id).map(Edition::getNom).orElse(id);
    }

    /** The grid as a CSV, without any person. */
    public String csv() {
        return RealisedVsPlannedAnalyzer.csv(report());
    }

    /**
     * One cell opened, holders named. A day not yet elapsed, or without a
     * reference, answers an empty detail saying so rather than an error.
     */
    public CellDetail detail(LocalDate day, String standId) {
        String standNom =
                referenceDataService.findStand(standId).map(Stand::getNom).orElse(standId);
        Realised realised = source.read();
        DayReference reference =
                dayReferences(clock.dateTime(), realised.plan(), publications(), planPublieService.snapshotPlans())
                        .stream()
                        .filter(each -> each.date().equals(day))
                        .findFirst()
                        .orElseGet(() -> DayReference.none(day));
        return RealisedVsPlannedAnalyzer.detail(reference, standId, standNom, realised);
    }

    /**
     * Whether the edition's event is over at {@code now}: its last timeslot
     * has ended. False for an edition without a timeslot — it has no event to
     * be over.
     */
    public boolean isOver(LocalDateTime now) {
        Map<LocalDate, DaySpan> spans = new HashMap<>();
        RealisedVsPlannedAnalyzer.addSpans(spans, referenceDataService.listCreneaux());
        return !spans.isEmpty() && spans.values().stream().allMatch(span -> span.isOverAt(now));
    }

    /**
     * The publications that are, right now, the reference in force of an
     * elapsed day, each with the first such day. Deleting one would move the
     * measure of a day already over — which a republication is not allowed
     * to do either — so {@link PlanSnapshotService#delete} refuses it. A day
     * measured against a late reference is not counted, and does not hold
     * its publication.
     */
    public Map<Long, LocalDate> referencesInForce() {
        Map<Long, LocalDate> references = new LinkedHashMap<>();
        // No realised plan: its seats stand on timeslots the referential
        // holds (a foreign key), so it would name no day the timeslots miss.
        for (DayReference day :
                dayReferences(clock.dateTime(), null, publications(), planPublieService.snapshotPlans())) {
            if (day.counted() && day.snapshotId() != null) {
                references.putIfAbsent(day.snapshotId(), day.date());
            }
        }
        return references;
    }

    /**
     * The measure the previous edition left — the one whose event ended last
     * before this edition's first day. None for an edition without a
     * timeslot: it has no dates to be « after » anything.
     */
    public PreviousEdition previousEdition() {
        // An organiser neither triggers the other editions' computation nor reads one
        // outside its rights: the previous edition then simply is not there.
        if (!editionContext.callerIsOrganiser()) {
            cacheOtherEditions();
        }
        JoursEvenement jours = JoursEvenement.of(referenceDataService.listCreneaux());
        if (jours.isEmpty()) {
            return PreviousEdition.NONE;
        }
        PreviousEdition previous = history.previous(jours.first());
        return previous.editionId() != null && !editionContext.visibleToCaller(previous.editionId())
                ? PreviousEdition.NONE
                : previous;
    }

    /**
     * Gives every <em>other</em> finished, published edition without a measure
     * the report that caches it: the previous edition is the one nobody opens
     * any more, so it would otherwise leave nothing to read. Each edition is
     * entered on its own and fails alone; the cheap tests come first, so an
     * edition never published, or not over, costs no computation per call.
     */
    private void cacheOtherEditions() {
        String current = editionContext.editionIdCourant();
        LocalDateTime now = clock.dateTime();
        for (Edition other : editionRepository.listEditions()) {
            if (other.getId().equals(current)) {
                continue;
            }
            try {
                editionContext.executeIn(other.getId(), () -> {
                    if (!history.exists() && !publications().isEmpty() && isOver(now)) {
                        report(now);
                    }
                });
            } catch (RuntimeException e) {
                // Named by id, never by display name, as the nightly sends do.
                LOG.errorf(e, "The realised measure of edition %s could not be cached", other.getId());
            }
        }
    }

    private List<Publication> publications() {
        return snapshotService.list().stream()
                .filter(meta -> meta.publieLe() != null)
                .map(meta -> new Publication(meta.id(), meta.publieLe()))
                .toList();
    }

    /**
     * Every elapsed day with its reference. The days are those of the
     * timeslots and of the plan, then those of each reference read: a day
     * whose every timeslot was deleted after the publication still had
     * promises, and its seats are « removed », not forgotten. A day's span is
     * read from today's timeslots when it has any, from the publication's
     * otherwise.
     */
    private List<DayReference> dayReferences(
            LocalDateTime now, PlanningEvenement realisedPlan, List<Publication> publications, SnapshotPlans plans) {
        Map<LocalDate, DaySpan> spans = new HashMap<>();
        RealisedVsPlannedAnalyzer.addSpans(spans, referenceDataService.listCreneaux());
        RealisedVsPlannedAnalyzer.addSpans(spans, realisedPlan);
        Map<LocalDate, DayReference> parJour = new LinkedHashMap<>();
        Deque<DaySpan> aLire = new ArrayDeque<>(elapsed(spans, now));
        while (!aLire.isEmpty()) {
            DaySpan span = aLire.poll();
            if (parJour.containsKey(span.date())) {
                continue;
            }
            DayReference reference = referenceOf(span, publications, plans);
            parJour.put(span.date(), reference);
            Map<LocalDate, DaySpan> autres = new HashMap<>();
            RealisedVsPlannedAnalyzer.addSpans(autres, reference.plan());
            autres.forEach(spans::putIfAbsent);
            for (LocalDate autre : autres.keySet()) {
                DaySpan retenu = spans.get(autre);
                if (!parJour.containsKey(autre) && retenu.isOverAt(now)) {
                    aLire.add(retenu);
                }
            }
        }
        List<DayReference> days = new ArrayList<>(parJour.values());
        days.sort(Comparator.comparing(DayReference::date));
        return days;
    }

    private static List<DaySpan> elapsed(Map<LocalDate, DaySpan> spans, LocalDateTime now) {
        return spans.values().stream()
                .filter(span -> span.isOverAt(now))
                .sorted(Comparator.comparing(DaySpan::date))
                .toList();
    }

    private static DayReference referenceOf(DaySpan span, List<Publication> publications, SnapshotPlans plans) {
        ChosenReference choisie =
                RealisedVsPlannedAnalyzer.referenceFor(span.startInstant(ZoneId.systemDefault()), publications);
        if (choisie == null) {
            return DayReference.none(span.date());
        }
        Optional<PlanningEvenement> plan = plans.plan(choisie.publication().snapshotId());
        return plan.map(lu -> new DayReference(
                        span.date(),
                        lu,
                        choisie.publication().publishedAt(),
                        choisie.late(),
                        choisie.publication().snapshotId()))
                .orElseGet(() -> DayReference.none(span.date()));
    }
}
