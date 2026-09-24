package dev.sylvain.planning.service.backup;

import dev.sylvain.planning.service.notification.Notification;
import dev.sylvain.planning.service.publication.AdminAddress;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.Scheduler;
import io.sentry.Sentry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.jboss.logging.Logger;

/**
 * Nightly {@code pg_dump} of the whole database, kept to a bounded number of
 * copies.
 *
 * <p>It exists for one failure: a wrong click that empties the data. The
 * screen's SQL import already replays a dump the application produced, but it
 * goes through a browser and needs the application standing; this one writes to
 * a volume, on its own, and what it writes is restored by the infrastructure
 * operator with {@code pg_restore} — <b>restoring is deliberately outside this
 * application</b>, see {@code docs/exploitation.md}.</p>
 *
 * <p>A failed backup never propagates: the scheduler would swallow it into a
 * log line nobody reads, and the next night would try again as if nothing had
 * happened. It is recorded instead, and the Paramètres screen shows it — the
 * only place where "the backup stopped working three weeks ago" becomes
 * visible before it matters.</p>
 *
 * <p>And a scheduled run that fails does not wait for someone to open that
 * screen: it fires {@link Notification.BackupFailed}, which mails
 * {@code MAIL_ADMIN}, and the first success after one or more failures fires
 * {@link Notification.BackupRecovered} to close the loop. Both go through the
 * notification path, best-effort, so an SMTP outage on top of a backup
 * failure is logged and stops there — and a second channel (a webhook) can
 * observe the same facts without this class changing. The exception itself
 * is also sent to the error tracker when one is configured.</p>
 */
@ApplicationScoped
public class BackupService {

    /** Names the job so its next fire time can be read back for the screen. */
    static final String JOB_IDENTITY = "database-backup";

    private static final Logger LOG = Logger.getLogger(BackupService.class);

    @Inject
    BackupConfiguration configuration;

    @Inject
    BackupRepository repository;

    @Inject
    PgDump pgDump;

    @Inject
    Scheduler scheduler;

    @Inject
    AdminAddress adminAddress;

    @Inject
    Event<Notification> notifications;

    /**
     * Failed attempts the database refused to record — the outage the alert
     * is most likely about. Kept here until the next write that goes through
     * folds them into the persisted streak: otherwise the success that follows
     * would find no failure on record and never send the « rétablie » mail the
     * failure mail promised, and every later count would be short. Lost on a
     * restart, which is the one thing memory cannot do better.
     */
    private final AtomicInteger unrecordedFailures = new AtomicInteger();

    /**
     * Said once at startup rather than every night: with a backup configured
     * and no admin address, a failure can only be seen on the screen. The
     * screen says it too ({@link BackupState#alertRecipientMissing()}).
     */
    void warnWhenNobodyCanBeAlerted(@Observes StartupEvent startup) {
        if (configuration.configured() && adminAddress.resolue().isEmpty()) {
            LOG.warn("Automatic backup is configured but MAIL_ADMIN is empty: a failed backup will alert nobody,"
                    + " it will only show on the Paramètres screen");
        }
    }

    /**
     * The nightly run. {@code SKIP} rather than a queue: a dump still running
     * when the next one is due means the database is far larger than this
     * schedule assumes, and stacking a second {@code pg_dump} on top of it
     * would make that worse rather than catch up.
     */
    @Scheduled(
            identity = JOB_IDENTITY,
            cron = "{planning.backup.cron}",
            timeZone = "{planning.backup.zone}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void scheduledBackup() {
        if (!configuration.configured()) {
            return;
        }
        boolean active;
        try {
            active = repository.isActive();
        } catch (RuntimeException e) {
            // The database does not answer: the likeliest cause of a failure at
            // four in the morning, and the one where the screen is unreachable
            // too. The switch cannot be read, so the run is attempted — pg_dump
            // will fail on the same outage — and the alert still goes out.
            LOG.error("Could not read the backup switch, attempting the backup anyway", e);
            active = true;
        }
        if (!active) {
            LOG.debug("Automatic backup is suspended, skipping the scheduled run");
            return;
        }
        Attempt attempt = attempt();
        if (!attempt.run().succeeded()) {
            Instant lastSuccess = attempt.streak().lastSuccessAt();
            notifications.fire(new Notification.BackupFailed(
                    attempt.run().attemptedAt().atZone(zoneId()),
                    attempt.run().message(),
                    lastSuccess != null ? lastSuccess.atZone(zoneId()) : newestDumpOnDisk(),
                    attempt.streak().consecutiveFailures()));
        } else if (attempt.streak().recovered()) {
            notifications.fire(new Notification.BackupRecovered(
                    attempt.run().attemptedAt().atZone(zoneId()),
                    attempt.run().file(),
                    attempt.streak().failuresBefore()));
        }
    }

    /**
     * Produces one dump and applies the rotation, recording the outcome either
     * way.
     *
     * @return what was recorded
     */
    public BackupRun run() {
        return attempt().run();
    }

    /** One attempt and the failure streak it leaves behind. */
    record Attempt(BackupRun run, BackupStreak streak) {}

    private Attempt attempt() {
        Instant attemptedAt = Instant.now();
        Path directory = configuration
                .targetDirectory()
                .orElseThrow(() -> new IllegalStateException("No backup directory is configured (BACKUP_DIR)"));
        BackupStore store = new BackupStore(directory);
        BackupRun outcome;
        try {
            store.prepare();
            Path dump = store.publish(LocalDateTime.ofInstant(attemptedAt, zoneId()), pgDump::dumpTo);
            List<String> deleted = store.rotate(configuration.retention());
            String name = dump.getFileName().toString();
            LOG.infof("Database backup written to %s (%d older dump(s) removed)", dump, deleted.size());
            // No message on success: the screen shows the file and its size,
            // and a sentence written here would be a French one shipped from a
            // backend whose UI is translated at runtime.
            outcome = new BackupRun(attemptedAt, true, name, null);
        } catch (IOException | RuntimeException e) {
            LOG.error("Database backup failed", e);
            reportToErrorTracker(e);
            outcome = new BackupRun(attemptedAt, false, null, reason(e));
        }
        return new Attempt(outcome, record(outcome));
    }

    /**
     * Records the attempt, or says the streak is unknown when the database
     * refuses the write — the alert must not depend on the very database whose
     * outage it may be reporting.
     */
    private BackupStreak record(BackupRun outcome) {
        int pending = unrecordedFailures.get();
        try {
            BackupStreak streak = repository.saveLastRun(outcome, pending);
            unrecordedFailures.addAndGet(-pending);
            return streak;
        } catch (RuntimeException e) {
            LOG.error("Could not record the backup attempt", e);
            if (!outcome.succeeded()) {
                unrecordedFailures.incrementAndGet();
            }
            return BackupStreak.unknown();
        }
    }

    /**
     * When the most recent dump still on disk was taken, for a failure mail
     * whose database has no date to give: unreachable, or migrated from a
     * version that only kept the last attempt — which, when it had failed,
     * left no trace of the successes before it. Read from the file name, not
     * the modification time, which a copy or a volume restore rewrites.
     *
     * @return {@code null} when the directory holds no dump or cannot be read
     */
    private ZonedDateTime newestDumpOnDisk() {
        Optional<Path> directory = configuration.targetDirectory();
        if (directory.isEmpty()) {
            return null;
        }
        try {
            return new BackupStore(directory.get())
                    .list().stream()
                            .findFirst()
                            .flatMap(file -> BackupStore.takenAt(file.name()))
                            .map(at -> at.atZone(zoneId()))
                            .orElse(null);
        } catch (IOException | RuntimeException e) {
            LOG.warn("Could not list the backup directory to date the last successful backup", e);
            return null;
        }
    }

    /**
     * Best-effort, like the solver's: no {@code SENTRY_DSN} makes it a no-op,
     * and a tracker that is down must not turn a recorded failure into a lost
     * one. Nothing to mask: a backup failure carries no URL and no token.
     */
    private static void reportToErrorTracker(Exception failure) {
        try {
            Sentry.captureException(failure, scope -> scope.setTag("job.type", "backup"));
        } catch (RuntimeException e) {
            LOG.warn("The backup failure could not be reported to the error tracker", e);
        }
    }

    /** The screen's whole view of the feature, in one read. */
    public BackupState state() {
        Optional<Path> directory = configuration.targetDirectory();
        List<BackupFile> files = List.of();
        String directoryError = null;
        if (directory.isPresent()) {
            try {
                files = new BackupStore(directory.get()).list();
            } catch (IOException | RuntimeException e) {
                directoryError = reason(e);
            }
        }
        return new BackupState(
                directory.isPresent(),
                directory.map(Path::toString).orElse(null),
                repository.isActive(),
                configuration.retention(),
                configuration.cron(),
                configuration.zone(),
                directory.isPresent() ? nextRun() : null,
                repository.lastRun(),
                files,
                directoryError,
                directory.isPresent() && adminAddress.resolue().isEmpty());
    }

    /** Suspends or resumes the nightly run, and returns the refreshed state. */
    public BackupState setActive(boolean active) {
        repository.setActive(active);
        return state();
    }

    /**
     * When the scheduler will fire next, or {@code null} when it is off — which
     * it is in tests, and would be in any deployment that disabled it.
     *
     * <p>The {@code isRunning()} guard is not belt and braces: with
     * {@code quarkus.scheduler.enabled=false},
     * {@code getScheduledJob()} does not answer {@code null}, it throws
     * {@code UnsupportedOperationException("Scheduler was not started")}. The
     * null check below never gets its chance, and since this method is reached
     * from {@link #state()}, the whole backup screen answers 500 — on the one
     * configuration this method claims to handle.</p>
     */
    private Instant nextRun() {
        if (!scheduler.isRunning()) {
            return null;
        }
        var job = scheduler.getScheduledJob(JOB_IDENTITY);
        return job == null || job.getNextFireTime() == null ? null : job.getNextFireTime();
    }

    private ZoneId zoneId() {
        try {
            return ZoneId.of(configuration.zone());
        } catch (RuntimeException e) {
            // The scheduler already refused this value; naming the file in the
            // system zone is better than losing the dump over its name.
            LOG.warnf("Unknown backup time zone %s, naming the dump in the system zone", configuration.zone());
            return ZoneId.systemDefault();
        }
    }

    private static String reason(Throwable e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}
