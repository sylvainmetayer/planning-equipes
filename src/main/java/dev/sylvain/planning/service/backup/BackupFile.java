package dev.sylvain.planning.service.backup;

import java.time.Instant;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * One dump sitting in the backup directory, as the Paramètres screen lists it.
 *
 * @param name      file name, whose timestamp is what orders the rotation
 * @param sizeBytes size on disk — a dump that suddenly shrinks is the first
 *                  visible sign that something went wrong upstream
 * @param createdAt last modification time of the file
 */
@Schema(requiredProperties = {"sizeBytes"})
public record BackupFile(String name, long sizeBytes, Instant createdAt) {}
