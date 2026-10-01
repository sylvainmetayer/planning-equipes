package dev.sylvain.planning.service.schema;

import java.time.Instant;

/**
 * One line of {@code version_applicative}: an application version that opened
 * this database, when it first did, and the latest migration applied then —
 * on a boot forced onto a database ahead, the latest one this version knows
 * ({@link SchemaCompatibility#recordedMigration}).
 *
 * @param latestMigration {@code null} on a history Flyway had not started yet,
 *                        which never happens once a boot got this far
 */
public record ApplicationVersion(String version, Instant firstStartedAt, String latestMigration) {}
