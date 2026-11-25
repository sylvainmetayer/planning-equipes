package dev.sylvain.planning.service.journal;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;

/**
 * The moment the author of a history line becomes believable.
 *
 * <p>Before the named accounts existed, every administrator signed in as the
 * configuration's one shared account, so a line said {@code admin} whoever
 * typed. From the migration that created {@code compte} (V130) on, the line
 * names the person's account. The date is that migration's own installation
 * time in Flyway's history — read through the application's {@link Flyway},
 * so from whatever schema and table it is configured with: derived, never
 * configured, and exact for the instance. A schema baselined at or above V130
 * has no V130 row; its baseline row, the first one at or above that version,
 * stands for it. The older lines are not rewritten.</p>
 */
@ApplicationScoped
public class ReliableAuthorSince {

    /** The migration that created the accounts, and from which the journal names one. */
    static final MigrationVersion ACCOUNTS_MIGRATION = MigrationVersion.fromVersion("130");

    private final Flyway flyway;

    private final JournalActionRepository repository;

    private static final long UNREAD = Long.MIN_VALUE;
    private static final long NOT_APPLIED = Long.MAX_VALUE;

    /** Flyway's history only moves at boot; read once, on first use (epoch millis, the precision Flyway keeps). */
    private volatile long installedMillis = UNREAD;

    @Inject
    public ReliableAuthorSince(Flyway flyway, JournalActionRepository repository) {
        this.flyway = flyway;
        this.repository = repository;
    }

    /**
     * The date to warn about, for the current edition: empty when the
     * migration is not applied, or when no line of the edition predates it —
     * a fresh install, or an edition born since, has no doubtful line, and a
     * notice there would only cast doubt on correct ones.
     */
    public Optional<Instant> forCurrentEdition() {
        return installed().filter(repository::hasLinesBefore);
    }

    Optional<Instant> installed() {
        long known = installedMillis;
        if (known == UNREAD) {
            // Two first calls may both read: the answer is the same.
            known = Arrays.stream(flyway.info().applied())
                    .filter(m -> m.getVersion() != null
                            && m.getVersion().isAtLeast(ACCOUNTS_MIGRATION.getVersion())
                            && m.getInstalledOn() != null)
                    .mapToLong(m -> m.getInstalledOn().getTime())
                    .findFirst()
                    .orElse(NOT_APPLIED);
            installedMillis = known;
        }
        return known == NOT_APPLIED ? Optional.empty() : Optional.of(Instant.ofEpochMilli(known));
    }
}
