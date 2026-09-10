package dev.sylvain.planning.service.backup;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Everything the automatic backup reads from the deployment: where the dumps
 * go, how many are kept, and how the dump itself is produced.
 *
 * <p>The directory is the switch. Left empty — the default — nothing is
 * scheduled: an application that writes nothing to disk keeps writing nothing
 * until an operator has mounted a volume and named it. Which is also why the
 * retention lives here and not in the database: both answers belong to the
 * machine holding the files, not to a screen.</p>
 *
 * <p>A retention outside its bounds <b>refuses the boot</b>. Clamping it would
 * be the worse failure: an operator who asked for thirty dumps and silently got
 * ten only finds out on the day they go looking for the one from three weeks
 * ago.</p>
 */
@ApplicationScoped
public class BackupConfiguration {

    public static final int RETENTION_MIN = 1;
    public static final int RETENTION_MAX = 30;
    public static final int RETENTION_DEFAULT = 10;

    @ConfigProperty(name = "planning.backup.directory")
    Optional<String> directory;

    @ConfigProperty(name = "planning.backup.retention", defaultValue = "10")
    int retention;

    @ConfigProperty(name = "planning.backup.cron", defaultValue = "0 0 4 * * ?")
    String cron;

    @ConfigProperty(name = "planning.backup.zone", defaultValue = "Europe/Paris")
    String zone;

    @ConfigProperty(name = "planning.backup.pg-dump", defaultValue = "pg_dump")
    String pgDump;

    @ConfigProperty(name = "planning.backup.timeout", defaultValue = "PT30M")
    Duration timeout;

    /** Fails the boot rather than let a nonsensical retention decide what gets deleted. */
    void validate(@Observes StartupEvent startup) {
        checkRetention(retention);
    }

    static void checkRetention(int retention) {
        if (retention < RETENTION_MIN || retention > RETENTION_MAX) {
            throw new IllegalStateException("planning.backup.retention (BACKUP_RETENTION) must be between "
                    + RETENTION_MIN + " and " + RETENTION_MAX + ", found: " + retention);
        }
    }

    /** {@code true} once a destination directory is configured; nothing runs before that. */
    public boolean configured() {
        return targetDirectory().isPresent();
    }

    public Optional<Path> targetDirectory() {
        return directory.map(String::trim).filter(value -> !value.isEmpty()).map(Path::of);
    }

    public int retention() {
        return retention;
    }

    public String cron() {
        return cron;
    }

    public String zone() {
        return zone;
    }

    public String pgDumpCommand() {
        return pgDump;
    }

    public Duration timeout() {
        return timeout;
    }
}
