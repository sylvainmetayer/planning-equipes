package dev.sylvain.planning.service.notification;

import dev.sylvain.planning.domain.ParametresNotifications;
import dev.sylvain.planning.service.EditionContext;
import dev.sylvain.planning.service.journal.JournalActionService;
import dev.sylvain.planning.service.journal.LoginJournalService;
import dev.sylvain.planning.service.mail.MailDeliveryRepository;
import dev.sylvain.planning.service.referentiel.ParametresService;
import dev.sylvain.planning.service.webhook.WebhookService;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.Scheduler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The one scheduled entry point of the three nightly notifications (issues
 * #298, #299, #300), and the one place that decides <b>whether an edition may
 * be written to at all</b>.
 *
 * <p>Modelled on {@code BackupService}, the application's first scheduled job:
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
 * <p><b>Only the active edition is served</b> (ADR 0072). Everything is
 * partitioned by edition and there is no {@code X-Edition-Id} on a scheduler
 * thread, so it is entered explicitly through {@link EditionContext#executeIn}.</p>
 */
@ApplicationScoped
public class NotificationsPlanifieesService {

    /** Names the job so the Paramètres screen can read its next fire time back. */
    static final String JOB_IDENTITY = "notifications-planifiees";

    private static final Logger LOG = Logger.getLogger(NotificationsPlanifieesService.class);

    private final EditionContext editionContext;

    private final ParametresService parametresService;

    private final RappelVeilleJob rappelVeille;

    private final RelanceConfirmationJob relanceConfirmation;

    private final AlerteEchangeJob alerteEchange;

    private final Scheduler scheduler;

    /** Applies the history's retention, once a night (issue #406). */
    private final JournalActionService journal;

    /** The outcome of each mail to an animateur, kept as long as the history. */
    private final MailDeliveryRepository deliveries;

    /** Applies the webhook journal's retention, on the same night. */
    private final WebhookService webhooks;

    /** The admin logins, kept as long as the history (ADR 0076). */
    private final LoginJournalService loginJournal;

    private final String zone;

    @Inject
    public NotificationsPlanifieesService(
            EditionContext editionContext,
            ParametresService parametresService,
            RappelVeilleJob rappelVeille,
            RelanceConfirmationJob relanceConfirmation,
            AlerteEchangeJob alerteEchange,
            Scheduler scheduler,
            JournalActionService journal,
            MailDeliveryRepository deliveries,
            WebhookService webhooks,
            LoginJournalService loginJournal,
            @ConfigProperty(name = "planning.notifications.zone") String zone) {
        this.editionContext = editionContext;
        this.parametresService = parametresService;
        this.rappelVeille = rappelVeille;
        this.relanceConfirmation = relanceConfirmation;
        this.alerteEchange = alerteEchange;
        this.scheduler = scheduler;
        this.journal = journal;
        this.deliveries = deliveries;
        this.webhooks = webhooks;
        this.loginJournal = loginJournal;
        this.zone = zone;
    }

    @Scheduled(
            identity = JOB_IDENTITY,
            cron = "{planning.notifications.cron}",
            timeZone = "{planning.notifications.zone}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void scheduledRun() {
        run();
    }

    /**
     * Purges the history, then does, for the <b>active</b> edition only,
     * whatever its own settings and the current time allow. Exposed for the
     * tests, which drive it directly rather than waiting for a cron.
     *
     * <p>Only the active edition sends (ADR 0072), and that is checked here and
     * nowhere else, so no job can be written that forgets it. It replaces the
     * per-edition arming switch, which let two armed editions with timeslots
     * on the same date each remind the same people the same night, with two
     * different plannings — neither could see the other, since a send is
     * claimed per edition. With no active edition (between two events),
     * nothing leaves at all.</p>
     *
     * @return how many messages actually left
     */
    public int run() {
        ZonedDateTime maintenant = ZonedDateTime.now(zoneId());
        purgeHistory();
        String active = editionContext.activeEditionId().orElse(null);
        if (active == null) {
            return 0;
        }
        try {
            return editionContext.executeIn(active, () -> forOneEdition(maintenant));
        } catch (RuntimeException e) {
            // Named by id, never by display name: an edition is not
            // personal data, but the exception below it might quote a row.
            LOG.errorf(e, "Scheduled notifications failed on edition %s", active);
            return 0;
        }
    }

    /**
     * Drops the history lines that have aged out (issue #406), and the
     * recorded outcomes of the mails sent to animateurs with them — the same
     * {@code JOURNAL_RETENTION}: a send result is a trace of the same kind,
     * and only the latest one per person is ever read —, the admin logins
     * with that same retention, and the webhook deliveries past their own.
     *
     * <p>Rides along with the nightly sweep rather than carrying a
     * {@code @Scheduled} of its own: every scheduler is one more thing to
     * configure, to time-zone and to explain, and a delete statement is not
     * worth one — the schedulers added since are argued, each for what it
     * does, in ADRs 0070 and 0074. Best-effort like
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
        try {
            int purgees = deliveries.purgeBefore(Instant.now().minus(journal.retention()));
            if (purgees > 0) {
                LOG.infof("Mail deliveries: %d rows older than %s dropped", purgees, journal.retention());
            }
        } catch (RuntimeException e) {
            LOG.error("The mail deliveries could not be purged; the nightly sends carry on", e);
        }
        // The admin logins, with the same retention: an address is personal
        // data, and the journal answers the same season-long question.
        try {
            int purgees = loginJournal.purgeBefore(Instant.now().minus(journal.retention()));
            if (purgees > 0) {
                LOG.infof("Admin logins: %d lines older than %s dropped", purgees, journal.retention());
            }
        } catch (RuntimeException e) {
            LOG.error("The admin logins could not be purged; the nightly sends carry on", e);
        }
        // The webhook deliveries past their thirty days, for the same reason
        // and on the same night: a table that grows, not a night that fails.
        try {
            int purgees = webhooks.purgeDeliveries();
            if (purgees > 0) {
                LOG.infof("Webhooks: %d deliveries older than thirty days dropped", purgees);
            }
        } catch (RuntimeException e) {
            LOG.error("The webhook deliveries could not be purged; the nightly sends carry on", e);
        }
    }

    /** What the active edition is allowed to send right now. */
    private int forOneEdition(ZonedDateTime maintenant) {
        ParametresNotifications parametres = parametresService.getNotifications();
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
        } catch (RuntimeException _) {
            LOG.warnf("Unknown notification time zone %s, falling back on the system zone", zone);
            return ZoneId.systemDefault();
        }
    }
}
