package dev.sylvain.planning.service.analyse;

import dev.sylvain.planning.domain.Edition;
import dev.sylvain.planning.service.EditionContext;
import dev.sylvain.planning.service.edition.EditionRepository;
import dev.sylvain.planning.service.espace.JourJClock;
import dev.sylvain.planning.service.journal.JournalActionService;
import dev.sylvain.planning.service.referentiel.JoursEvenement;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.LocalDateTime;
import org.jboss.logging.Logger;

/**
 * The application's third {@code @Scheduled}: once a night, freezes the
 * Réalisé vs planifié measure of every edition whose event is over, into
 * {@code kpi_realise}, where the next edition reads it (ADR 0070).
 *
 * <p>Built on the conventions of the other two, {@code BackupService} and
 * {@code NotificationsPlanifieesService}: a configurable cron and time zone,
 * {@code SKIP} on overlap, a failure logged rather than propagated, and each
 * edition entered through {@link EditionContext#executeIn} — there is no
 * {@code X-Edition-Id} on a scheduler thread — inside its own
 * {@code try/catch}, so one edition's failure does not cost the others their
 * measure.</p>
 *
 * <p>An edition is frozen when its last timeslot — an edition stores no
 * dates — has ended, and something was published: without a publication
 * there is no promise to measure, and the job writes nothing rather than a
 * row of zeros. It reads <b>no</b> notification setting: it sends nothing,
 * and the arming of the nightly mails is what stands between those mails and
 * last year's volunteers — a guard this job has no reason to answer to.</p>
 *
 * <p><b>It writes once.</b> An edition that already has its rows is skipped:
 * the measure is the one of the first night after the event, and {@code
 * fige_le} says when that was. A later edit of the edition — an absence
 * recorded the morning after, a timeslot deleted — does not rewrite it; an
 * operator who wants it rewritten deletes the edition's rows by SQL (see
 * {@code docs/exploitation.md}), and the next night freezes again. An edition
 * whose report says the past is not frozen ({@code PASSE_FIGE=false}) is
 * skipped and logged: a solve may have rewritten its elapsed days, and a
 * measure written once must not be written from a realised nobody
 * guarantees. Rows survive the deletion of their edition — the job simply no
 * longer sees it.</p>
 */
@ApplicationScoped
public class RealisedFreezeJob {

    private static final Logger LOG = Logger.getLogger(RealisedFreezeJob.class);

    private final EditionRepository editionRepository;

    private final EditionContext editionContext;

    private final ReferenceDataService referenceDataService;

    private final RealisedVsPlannedService realisedService;

    private final RealisedHistoryRepository history;

    private final JourJClock clock;

    private final JournalActionService journal;

    @Inject
    public RealisedFreezeJob(
            EditionRepository editionRepository,
            EditionContext editionContext,
            ReferenceDataService referenceDataService,
            RealisedVsPlannedService realisedService,
            RealisedHistoryRepository history,
            JourJClock clock,
            JournalActionService journal) {
        this.editionRepository = editionRepository;
        this.editionContext = editionContext;
        this.referenceDataService = referenceDataService;
        this.realisedService = realisedService;
        this.history = history;
        this.clock = clock;
        this.journal = journal;
    }

    @Scheduled(
            identity = "realise-fige",
            cron = "{planning.realise.cron}",
            timeZone = "{planning.realise.zone}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void scheduledRun() {
        run(clock.dateTime());
    }

    /**
     * Freezes every finished edition not frozen yet, as of {@code now}.
     * Exposed for the tests, which drive it with the moment they need rather
     * than waiting for a cron.
     *
     * @return how many editions were frozen by this run
     */
    public int run(LocalDateTime now) {
        int frozen = 0;
        for (Edition edition : editionRepository.listEditions()) {
            try {
                if (Boolean.TRUE.equals(editionContext.executeIn(edition.getId(), () -> freeze(edition, now)))) {
                    frozen++;
                }
            } catch (RuntimeException e) {
                // Named by id, never by display name, as the nightly sends do.
                LOG.errorf(
                        e,
                        "The realised measure of edition %s could not be frozen; the other editions carry on",
                        edition.getId());
            }
        }
        return frozen;
    }

    /** One edition, inside its own scope: frozen once, when its event is over and was published. */
    private boolean freeze(Edition edition, LocalDateTime now) {
        if (history.exists() || !realisedService.isOver(now)) {
            return false;
        }
        RealisedVsPlanned report = realisedService.report(now);
        if (!report.referenceAvailable()) {
            return false;
        }
        if (!report.frozenPast()) {
            LOG.warnf(
                    "The realised measure of edition %s is not frozen: the frozen past is switched off"
                            + " (PASSE_FIGE=false), so its elapsed days are not guaranteed",
                    edition.getId());
            return false;
        }
        JoursEvenement jours = JoursEvenement.of(referenceDataService.listCreneaux());
        if (history.insert(edition.getNom(), jours.first(), jours.last(), report, Instant.now()) == 0) {
            // Another run won the insert: it is the one that freezes, and journals.
            return false;
        }
        journal.recordSystemAction("REALISE_FIGE", null);
        return true;
    }
}
