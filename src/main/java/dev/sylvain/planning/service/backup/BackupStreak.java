package dev.sylvain.planning.service.backup;

import java.time.Instant;

/**
 * The run of failed backups the last attempt belongs to, as the alert mails
 * need it.
 *
 * @param consecutiveFailures failed attempts since the last success, this one
 *                            included; zero after a success
 * @param failuresBefore      the same count before this attempt — non-zero on a
 *                            success means the backup just recovered
 * @param lastSuccessAt       when the last successful backup ran, {@code null}
 *                            when none ever did
 */
public record BackupStreak(int consecutiveFailures, int failuresBefore, Instant lastSuccessAt) {

    static BackupStreak none() {
        return new BackupStreak(0, 0, null);
    }

    /**
     * The database could not be read or written: how many nights failed and
     * when the last success was are not known. Zero failures says "unknown" to
     * the mail, which then names no count and no date.
     */
    static BackupStreak unknown() {
        return new BackupStreak(0, 0, null);
    }

    /** This attempt succeeded after one or more failures. */
    public boolean recovered() {
        return consecutiveFailures == 0 && failuresBefore > 0;
    }
}
