package dev.sylvain.planning.service.backup;

import java.time.Instant;

/**
 * Outcome of the last backup attempt, successful or not.
 *
 * <p>Persisted rather than kept in memory: the run happens at four in the
 * morning and is read hours later, possibly after a restart, by someone whose
 * question is "did the backup run last night" — a question the container's
 * logs answer badly and an empty in-memory field not at all.
 *
 * @param attemptedAt when the attempt started, {@code null} when none ever ran
 * @param succeeded   whether it produced a dump
 * @param file        the dump it wrote, {@code null} on failure
 * @param message     what happened, in the words shown on the Paramètres screen
 */
public record BackupRun(Instant attemptedAt, boolean succeeded, String file, String message) {

    /** Nothing has run yet on this instance. */
    public static BackupRun never() {
        return new BackupRun(null, false, null, null);
    }

    public boolean ranAtLeastOnce() {
        return attemptedAt != null;
    }
}
