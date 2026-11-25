package dev.sylvain.planning.service.journal;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import org.flywaydb.core.Flyway;

/**
 * The moment the author of a history line becomes believable.
 *
 * <p>Before the named accounts existed, every administrator signed in as the
 * configuration's one shared account, so a line said {@code admin} whoever
 * typed. From the migration that created {@code compte} (V130) on, the line
 * names the person's account. The date is that migration's own installation
 * time in Flyway's history: derived, never configured, and exact for the
 * instance — a fresh install dates from its first boot, so none of its lines is
 * doubtful. The older lines are not rewritten.</p>
 */
@ApplicationScoped
public class ReliableAuthorSince {

    /** The migration that created the accounts, and from which the journal names one. */
    static final String ACCOUNTS_MIGRATION = "130";

    private final Flyway flyway;

    @Inject
    public ReliableAuthorSince(Flyway flyway) {
        this.flyway = flyway;
    }

    /** Empty when the migration is not applied (an instance that has not booted this version yet). */
    public Optional<Instant> instant() {
        return Arrays.stream(flyway.info().applied())
                .filter(m -> m.getVersion() != null
                        && ACCOUNTS_MIGRATION.equals(m.getVersion().getVersion()))
                .map(m -> m.getInstalledOn().toInstant())
                .findFirst();
    }
}
