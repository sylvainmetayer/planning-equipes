package dev.sylvain.planning.service.notification;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import dev.sylvain.planning.domain.Edition;
import dev.sylvain.planning.service.journal.JournalActionService;
import dev.sylvain.planning.domain.ParametresNotifications;
import dev.sylvain.planning.service.EditionContext;
import dev.sylvain.planning.service.edition.EditionRepository;
import dev.sylvain.planning.service.referentiel.ParametresService;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.Scheduler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The one scheduled entry point of the three nightly notifications (issues
 * #298, #299, #300), and the one place that decides <b>whether an edition may
 * be written to at all</b>.
 *
 * <p>Modelled on {@code BackupService}, the application's other scheduled job:
 * a configurable cron and time zone, {@code SKIP} on overlap rather than a
 * queue, and a failure that is logged rather than propagated — the scheduler
 * would swallow it into a line nobody reads, and the next run would try again
 * as if nothing had happened.</p>
 *
 * <p>It runs <b>hourly</b>, not once a night, and that is what lets the J-1
 * reminder respect a per-edition sending time: a single cron cannot fire at
 * four different hours for four editions, so the job wakes up often and each
 * edition sends when its own hour has passed. Doing so is only safe because
 * every send is claimed in {@link JournalNotificationsRepository} first —
 * running twice, or twelve times, writes to nobody twice.</p>
 *
 * <p><b>The edition loop is the delicate part.</b> Everything is partitioned by
 * edition and there is no {@code X-Edition-Id} on a scheduler thread, so each
 * edition is entered explicitly through {@link EditionContext#executeIn} and
 * one edition's failure must not stop the next one — hence the
 * {@code try/catch} inside the loop rather than around it.</p>
 */
@ApplicationScoped
public class NotificationsPlanifieesService {

    /** Names the job so the Paramètres screen can read its next fire time back. */
    static final String JOB_IDENTITY = "notifications-planifiees";

    private static final Logger LOG = Logger.getLogger(NotificationsPlanifieesService.class);

    @Inject
    EditionRepository editionRepository;

    @Inject
    EditionContext editionContext;

    @Inject
    ParametresService parametresService;

    @Inject
    RappelVeilleJob rappelVeille;

    @Inject
    RelanceConfirmationJob relanceConfirmation;

    @Inject
    AlerteEchangeJob alerteEchange;

    @Inject
    Scheduler scheduler;

    /** Applies the history's retention, once a night (issue #406). */
    @Inject
    JournalActionService journal;

    @ConfigProperty(name = "planning.notifications.zone")
    String zone;

    @ConfigProperty(name = "planning.notifications.cron")
    String cron;

    @Scheduled(identity = JOB_IDENTITY, cron = "{planning.notifications.cron}",
            timeZone = "{planning.notifications.zone}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void scheduledRun() {
        run();
    }

    /**
     * Walks every edition and does, for each armed one, whatever its own
     * settings and the current time allow. Exposed for the tests, which drive
     * it directly rather than waiting for a cron.
     *
     * @return how many messages actually left, across every edition
     */
    public int run() {
        ZonedDateTime maintenant = ZonedDateTime.now(zoneId());
        purgeHistory();
        int envois = 0;
        for (Edition edition : editionRepository.listEditions()) {
            try {
                envois += editionContext.executeIn(edition.getId(), () -> forOneEdition(maintenant));
            } catch (RuntimeException e) {
                // Named by id, never by display name: an edition is not
                // personal data, but the exception below it might quote a row.
                LOG.errorf(e, "Scheduled notifications failed on edition %s; the other editions carry on",
                        edition.getId());
            }
        }
        return envois;
    }

    /**
     * Drops the history lines that have aged out (issue #406).
     *
     * <p>Rides along with the nightly sweep rather than carrying a second
     * {@code @Scheduled}: this application has exactly two schedulers, the
     * backup and this one, and a third one for a delete statement would be a
     * third thing to configure, to time-zone and to explain. Best-effort like
     * everything else here — an unpurged journal is a table that grows, not a
     * night that fails.</p>
     */
    private void purgeHistory() {
        try {
            int purgees = journal.purge();
            if (purgees > 0) {
                LOG.infof("History: %d lines older than %s dropped", purgees, journal.retention());
            }
        } catch (RuntimeException e) {
            LOG.error("The history could not be purged; the nightly sends carry on", e);
        }
    }

    /**
     * What one edition is allowed to send right now.
     *
     * <p>The {@code actives} guard is checked here and nowhere else, so no job
     * can be written that forgets it: an edition nobody armed is left alone
     * before any of them is even called. Without it, the first night after a
     * deployment would remind last year's volunteers that they are on duty
     * tomorrow.</p>
     */
    private int forOneEdition(ZonedDateTime maintenant) {
        ParametresNotifications parametres = parametresService.getNotifications();
        if (!parametres.actives()) {
            return 0;
        }
        int envois = rappelVeille.run(parametres, maintenant)
                + relanceConfirmation.run(parametres, maintenant.toInstant())
                + alerteEchange.run(parametres, maintenant.toInstant());
        if (envois > 0) {
            // The third seam of the history (issue #406): what the application
            // did on its own. Only when something actually left — a night that
            // sent nothing is not an action, and a line every hour on every
            // edition would bury the ones a reader is looking for.
            journal.recordSystemAction("NOTIFICATIONS_ENVOYEES", null);
        }
        return envois;
    }

    /**
     * When the scheduler will fire next, or {@code null} when it is off —
     * which it is in tests, and would be in a deployment that disabled it.
     *
     * <p>The {@code isRunning()} guard is not belt and braces: with
     * {@code quarkus.scheduler.enabled=false}, {@code getScheduledJob()}
     * throws rather than answering {@code null} — the same trap
     * {@code BackupService} documents.</p>
     */
    public Instant nextRun() {
        if (!scheduler.isRunning()) {
            return null;
        }
        var job = scheduler.getScheduledJob(JOB_IDENTITY);
        return job == null ? null : job.getNextFireTime();
    }

    /** The zone every "is it that hour yet?" question is answered in. */
    public ZoneId zoneId() {
        try {
            return ZoneId.of(zone);
        } catch (RuntimeException e) {
            LOG.warnf("Unknown notification time zone %s, falling back on the system zone", zone);
            return ZoneId.systemDefault();
        }
    }
}
