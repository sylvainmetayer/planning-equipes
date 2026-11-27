package dev.sylvain.planning.observability;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import javax.sql.DataSource;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.jboss.logging.Logger;

/**
 * The readiness probe, and above all the answer to "does the database answer?".
 * Reading the Flyway history needs a connection, so an unreachable database is
 * DOWN here — which is why Quarkus's own datasource check is switched off: it
 * would say the same thing, plus the driver's error message and the host in it.
 *
 * <p>The schema half is defence in depth. {@code migrate-at-start} refuses to
 * boot on a pending or failed migration, so an interrupted migration stops
 * startup rather than producing a DOWN instance ({@code FLYWAY_REPAIR_AT_START}
 * is what clears it). What this check adds is a running instance whose history
 * changed under it — another instance, or an operator, migrating the same
 * database with a newer binary.</p>
 *
 * <p>Migrations no longer change once the application is up (migrate-at-start
 * is done, the schema guard has read the history), so the first UP verdict is
 * kept for good: later probes answer UP without opening Flyway, which would
 * otherwise open a connection and write its "Database: jdbc:…" line on every
 * poll. Only a DOWN verdict is re-read, at most once per {@link #CACHE_TTL},
 * until the history is up to date. Once UP, each probe runs a bare
 * {@code SELECT 1} on the datasource instead, so a database that goes down
 * later still turns readiness DOWN. The response never carries the driver's
 * message (it holds the host); it goes to the log, once per outage.</p>
 *
 * <p>A failed migration <em>of a newer binary</em> counts like any other,
 * except under {@code ALLOW_SCHEMA_AHEAD=true}: the boot that escape hatch
 * forced was refused precisely because of the future rows, the failed ones
 * named as such, and the operator accepted them. Counting them here would turn
 * the forced boot into an instance that never becomes ready.</p>
 *
 * <p>The response carries two counts and nothing else: no version, no script
 * name, no JDBC URL. The probe is public, so every anonymous caller may hit it:
 * a DOWN verdict is kept for {@link #CACHE_TTL} rather than recomputed by a full
 * {@code flyway.info()} on each request.</p>
 */
@Readiness
@ApplicationScoped
public class FlywayMigrationsReadinessCheck implements HealthCheck {

    static final String NAME = "Migrations Flyway";

    /** Short enough for an orchestrator polling every few seconds, long enough to absorb a burst. */
    static final Duration CACHE_TTL = Duration.ofSeconds(5);

    private static final int PING_TIMEOUT_SECONDS = 2;

    private static final Logger LOG = Logger.getLogger(FlywayMigrationsReadinessCheck.class);

    private final Flyway flyway;

    private final DataSource dataSource;

    private final boolean allowAhead;

    private final AtomicBoolean pingFailing = new AtomicBoolean();

    @Inject
    public FlywayMigrationsReadinessCheck(
            Flyway flyway,
            DataSource dataSource,
            @ConfigProperty(name = "planning.schema.allow-ahead", defaultValue = "false") boolean allowAhead) {
        this.flyway = flyway;
        this.dataSource = dataSource;
        this.allowAhead = allowAhead;
    }

    /** Monotonic, replaceable by a test. */
    LongSupplier nanoTime = System::nanoTime;

    /** An immutable pair published through an atomic reference: readers see a whole verdict or none. */
    private record Verdict(HealthCheckResponse response, long computedAt) {}

    private final AtomicReference<Verdict> cached = new AtomicReference<>();

    @Override
    public HealthCheckResponse call() {
        long now = nanoTime.getAsLong();
        Verdict current = cached.get();
        if (current != null && current.response().getStatus() == HealthCheckResponse.Status.UP) {
            return pingResponse(current.response());
        }
        if (current != null && now - current.computedAt() < CACHE_TTL.toNanos()) {
            return current.response();
        }
        // Two concurrent misses may both read the history; both write an
        // equally fresh verdict, which is harmless and needs no lock that a
        // hung database could hold every probe thread on.
        HealthCheckResponse response = readHistory();
        cached.set(new Verdict(response, now));
        return response;
    }

    /** The kept UP verdict while the database answers a {@code SELECT 1}, a bare DOWN otherwise. */
    private HealthCheckResponse pingResponse(HealthCheckResponse up) {
        try {
            ping();
            if (pingFailing.getAndSet(false)) {
                LOG.info("Database answers again, readiness back UP");
            }
            return up;
        } catch (SQLException | RuntimeException e) {
            if (!pingFailing.getAndSet(true)) {
                LOG.warnf("Database unreachable, reporting not ready: %s", e.getMessage());
            }
            return HealthCheckResponse.named(NAME).down().build();
        }
    }

    /** The cheapest round trip, with a short timeout so a hung database cannot pile probes up. */
    void ping() throws SQLException {
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            statement.setQueryTimeout(PING_TIMEOUT_SECONDS);
            statement.execute("SELECT 1");
        }
    }

    /** One actual read of the Flyway history, uncached. */
    HealthCheckResponse readHistory() {
        try {
            var info = flyway.info();
            return evaluate(info.pending(), info.all(), allowAhead);
        } catch (RuntimeException e) {
            // The history table could not even be read: not ready, and the
            // reason stays in the log, not in a public response.
            LOG.warnf("Flyway history unreadable, reporting not ready: %s", e.getMessage());
            return HealthCheckResponse.named(NAME).down().build();
        }
    }

    /** Pure verdict, so the rule is tested without a database. */
    static HealthCheckResponse evaluate(MigrationInfo[] pending, MigrationInfo[] all, boolean allowAhead) {
        long failed = Arrays.stream(all)
                .filter(m -> m.getState().isFailed())
                .filter(m -> !(allowAhead && m.getState() == MigrationState.FUTURE_FAILED))
                .count();
        return HealthCheckResponse.named(NAME)
                .status(pending.length == 0 && failed == 0)
                .withData("pending", pending.length)
                .withData("failed", failed)
                .build();
    }
}
