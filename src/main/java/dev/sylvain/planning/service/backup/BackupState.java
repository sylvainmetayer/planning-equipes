package dev.sylvain.planning.service.backup;

import java.time.Instant;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * What the Paramètres screen shows about the automatic backup.
 *
 * @param configured     whether a destination directory is set; everything else
 *                       is inert while this is {@code false}
 * @param directory      that directory, so an operator can check the screen
 *                       against the volume they mounted, {@code null} when none
 * @param active         the suspend switch, the one value the screen writes
 * @param retention      how many dumps are kept, from the environment
 * @param cron           the schedule, as configured
 * @param zone           the time zone that schedule is read in
 * @param nextRun        when the next backup is due, {@code null} when nothing
 *                       is scheduled
 * @param lastRun        outcome of the last attempt
 * @param files          the dumps currently on disk, most recent first
 * @param directoryError why the directory could not be listed, {@code null}
 *                       when it could
 * @param alertRecipientMissing a backup is configured but {@code MAIL_ADMIN} is
 *                       empty: a failed night alerts nobody, only this
 *                       screen shows it
 */
@Schema(requiredProperties = {"active", "configured", "retention", "alertRecipientMissing"})
public record BackupState(
        boolean configured,
        String directory,
        boolean active,
        int retention,
        String cron,
        String zone,
        Instant nextRun,
        BackupRun lastRun,
        List<BackupFile> files,
        String directoryError,
        boolean alertRecipientMissing) {}
