package dev.sylvain.planning.service.backup;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.jboss.logging.Logger;

import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.Scheduler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

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

    /**
     * The nightly run. {@code SKIP} rather than a queue: a dump still running
     * when the next one is due means the database is far larger than this
     * schedule assumes, and stacking a second {@code pg_dump} on top of it
     * would make that worse rather than catch up.
     */
    @Scheduled(identity = JOB_IDENTITY, cron = "{planning.backup.cron}", timeZone = "{planning.backup.zone}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void scheduledBackup() {
        if (!configuration.configured()) {
            return;
        }
        if (!repository.isActive()) {
            LOG.debug("Automatic backup is suspended, skipping the scheduled run");
            return;
        }
        run();
    }

    /**
     * Produces one dump and applies the rotation, recording the outcome either
     * way.
     *
     * @return what was recorded
     */
    public BackupRun run() {
        Instant attemptedAt = Instant.now();
        Path directory = configuration.targetDirectory()
                .orElseThrow(() -> new IllegalStateException(
                        "No backup directory is configured (BACKUP_DIR)"));
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
            outcome = new BackupRun(attemptedAt, false, null, reason(e));
        }
        repository.saveLastRun(outcome);
        return outcome;
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
                directoryError);
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
