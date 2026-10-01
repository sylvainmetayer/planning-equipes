package dev.sylvain.planning.service.schema;

import io.quarkus.runtime.StartupEvent;
import io.sentry.Sentry;
import io.sentry.SentryLevel;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.interceptor.Interceptor;
import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.jboss.logging.Logger;

/**
 * Refuses to boot on a database <b>ahead</b> of the binary — an image rolled
 * back onto a schema a later version migrated — and records, on every boot
 * that gets through, which application version opened the database.
 *
 * <p>The schema is forward-only ({@code docs/versioning.md} § 2): an older
 * binary on a newer schema meets NOT NULL columns it never writes, constraints
 * it never heard of, tables under another name — errors at run time, during
 * the event, or worse, writes that succeed. A refused boot is seen at once.
 * The form is that of the other boot checks ({@code DefaultSecrets},
 * {@code RequiredMentionsLegales}, the {@code BACKUP_RETENTION} bounds): an
 * {@link IllegalStateException} whose message names what is wrong and the way
 * out. Unlike those, it holds in every launch mode: a developer's database
 * left ahead by another branch is the same hazard, and Flyway refused it
 * before this class existed.</p>
 *
 * <p><b>Why Flyway does not refuse it itself any more.</b> On the Flyway
 * Quarkus ships, an unset {@code ignore-migration-patterns} means "ignore
 * nothing", so a future migration used to fail {@code validate} with « applied
 * migration not resolved locally », and Flyway's own hint — run repair — is
 * the trap: {@code repair} <em>deletes</em> the history rows of migrations it
 * does not know, after which the old binary boots silently on the new schema,
 * and the next upgrade replays those migrations onto tables that already
 * exist. {@code application.properties} therefore sets
 * {@code quarkus.flyway.ignore-migration-patterns=*:future}: validation lets
 * them through, and this class is the one to decide, with a message an
 * operator can act on and an escape hatch.</p>
 *
 * <p><b>Repair runs before this class.</b> The pattern spares the
 * <em>successful</em> future rows from repair, not the failed ones:
 * {@code repair} removes every failed row first, a newer binary's included.
 * {@code FLYWAY_REPAIR_AT_START} runs repair before migrate, both before
 * {@link StartupEvent}, so a half-migrated newer schema would reach this
 * observer already erased from the history. {@link SchemaCompatibilityRepairGuard}
 * therefore takes the same decision, through {@link #enforce}, as a Flyway
 * callback right before repair touches anything.</p>
 *
 * <p><b>The escape hatch</b>, {@code ALLOW_SCHEMA_AHEAD=true}, is the same
 * kind of switch as {@code FLYWAY_REPAIR_AT_START}: for an operator who knows
 * why the schema is ahead (an additive migration the older code survives),
 * turned on for one boot and back off afterwards. The boot then goes on with
 * the same sentence at {@code WARN} and in the error tracker.</p>
 *
 * <p><b>Ordering.</b> Flyway's {@code migrate-at-start} runs in a
 * {@code RUNTIME_INIT} build step that produces a {@code ServiceStartBuildItem};
 * Quarkus fires {@link StartupEvent} only once every such step has run, and
 * opens the HTTP socket only after the event — so whatever the priority, this
 * reads a history Flyway has finished with, and no request is served before it
 * decides. The priority is about the other observers: {@code LIBRARY_BEFORE}
 * puts this one ahead of every application observer left at the default
 * (the solver queue replay first among them), and after
 * {@code SentryInitializer}, which runs at {@code PLATFORM_BEFORE} so that the
 * warning has somewhere to go. The record is a second observer, at
 * {@code PLATFORM_AFTER}: past every other boot check — {@code DefaultSecrets},
 * {@code RequiredMentionsLegales}, the backup and solve budget bounds and the
 * authentication configuration, all at the default priority — so that a boot one
 * of them refuses is not written down as a version that opened the database.
 * An observer that throws ends the event, and this one is then never called.</p>
 */
@ApplicationScoped
public class SchemaCompatibilityGuard {

    private static final Logger LOG = Logger.getLogger(SchemaCompatibilityGuard.class);

    private final Flyway flyway;

    private final ApplicationVersionRepository versions;

    private final String binaryVersion;

    private final boolean allowAhead;

    private final Clock clock;

    /** What {@link #check} let through, for {@link #recordAtStartup} once every other check has passed. */
    private final AtomicReference<Admission> admitted = new AtomicReference<>();

    /** @param migration what {@link SchemaCompatibility#recordedMigration} says to write down */
    private record Admission(MigrationVersion migration) {}

    @Inject
    public SchemaCompatibilityGuard(
            Flyway flyway,
            ApplicationVersionRepository versions,
            @ConfigProperty(name = "quarkus.application.version") String binaryVersion,
            @ConfigProperty(name = "planning.schema.allow-ahead", defaultValue = "false") boolean allowAhead) {
        this(flyway, versions, binaryVersion, allowAhead, Clock.systemUTC());
    }

    SchemaCompatibilityGuard(
            Flyway flyway,
            ApplicationVersionRepository versions,
            String binaryVersion,
            boolean allowAhead,
            Clock clock) {
        this.flyway = flyway;
        this.versions = versions;
        this.binaryVersion = binaryVersion;
        this.allowAhead = allowAhead;
        this.clock = clock;
    }

    void checkAtStartup(@Observes @Priority(Interceptor.Priority.LIBRARY_BEFORE) StartupEvent startup) {
        check(allowAhead);
    }

    void recordAtStartup(@Observes @Priority(Interceptor.Priority.PLATFORM_AFTER) StartupEvent startup) {
        recordAdmitted();
    }

    /**
     * The whole boot decision, the escape hatch passed in so a test can play
     * both answers on the running application without restarting it.
     *
     * @return {@code COMPATIBLE} or {@code WARN}; a refusal is never returned, it is thrown
     * @throws IllegalStateException when the database is ahead and {@code allowAhead} is off
     */
    SchemaCompatibility.Outcome check(boolean allowAhead) {
        admitted.set(null);
        SchemaCompatibility.Verdict verdict =
                SchemaCompatibility.assess(flyway.info().all());
        SchemaCompatibility.Outcome outcome = enforce(verdict, allowAhead, binaryVersion, versions, "démarrage forcé");
        admitted.set(new Admission(SchemaCompatibility.recordedMigration(verdict, outcome)));
        return outcome;
    }

    /** Writes down the version {@link #check} last let through; nothing after a refusal. */
    void recordAdmitted() {
        Admission admission = admitted.get();
        if (admission != null) {
            recordVersion(admission.migration());
        }
    }

    /**
     * The decision both seams share — this observer and the repair callback:
     * a refusal is thrown, a forced pass is logged and sent to the error tracker.
     *
     * @param forced what goes on despite the database being ahead, in the operator's words
     * @return {@code COMPATIBLE} or {@code WARN}
     * @throws IllegalStateException when the database is ahead and {@code allowAhead} is off
     */
    static SchemaCompatibility.Outcome enforce(
            SchemaCompatibility.Verdict verdict,
            boolean allowAhead,
            String binaryVersion,
            ApplicationVersionRepository versions,
            String forced) {
        SchemaCompatibility.Outcome outcome = SchemaCompatibility.decide(verdict, allowAhead);
        if (outcome != SchemaCompatibility.Outcome.COMPATIBLE) {
            String message = SchemaCompatibility.describe(verdict, binaryVersion, appliedBy(versions, verdict));
            if (outcome == SchemaCompatibility.Outcome.REFUSE) {
                throw new IllegalStateException(message);
            }
            String warning = "ALLOW_SCHEMA_AHEAD=true, " + forced + ". " + message;
            LOG.warn(warning);
            Sentry.captureMessage(warning, SentryLevel.WARNING);
        }
        return outcome;
    }

    /** Best effort: the history is traceability, and a message without it still says what to do. */
    private static Optional<String> appliedBy(
            ApplicationVersionRepository versions, SchemaCompatibility.Verdict verdict) {
        try {
            return versions.firstVersionAt(verdict.latestApplied().getVersion());
        } catch (RuntimeException e) {
            LOG.warnf("Could not read which version applied V%s: %s", verdict.latestApplied(), e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Never fails the boot: an instance that cannot write one line of
     * traceability is still an instance that works.
     */
    private void recordVersion(MigrationVersion migration) {
        String latest = migration == null ? null : migration.getVersion();
        try {
            if (versions.recordStart(binaryVersion, latest, clock.instant())) {
                LOG.infof(
                        "Application version %s recorded as opening this database (schema at V%s)",
                        binaryVersion, latest);
            }
        } catch (RuntimeException e) {
            LOG.error("Could not record the application version opening this database", e);
        }
    }
}
