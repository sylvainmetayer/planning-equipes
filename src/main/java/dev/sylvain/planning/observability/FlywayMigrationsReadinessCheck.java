package dev.sylvain.planning.observability;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Arrays;
import java.util.function.LongSupplier;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
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
 * <p>The response carries two counts and nothing else: no version, no script
 * name, no JDBC URL. The probe is public, so every anonymous caller may hit it:
 * the verdict is kept for {@link #CACHE_TTL} rather than recomputed by a full
 * {@code flyway.info()} on each request.</p>
 */
@Readiness
@ApplicationScoped
public class FlywayMigrationsReadinessCheck implements HealthCheck {

    static final String NAME = "Migrations Flyway";

    /** Short enough for an orchestrator polling every few seconds, long enough to absorb a burst. */
    static final Duration CACHE_TTL = Duration.ofSeconds(5);

    private static final Logger LOG = Logger.getLogger(FlywayMigrationsReadinessCheck.class);

    private final Flyway flyway;

    @Inject
    public FlywayMigrationsReadinessCheck(Flyway flyway) {
        this.flyway = flyway;
    }

    /** Monotonic, replaceable by a test. */
    LongSupplier nanoTime = System::nanoTime;

    /** An immutable pair published through a volatile field: readers see a whole verdict or none. */
    private record Verdict(HealthCheckResponse response, long computedAt) {}

    private volatile Verdict cached;

    @Override
    public HealthCheckResponse call() {
        long now = nanoTime.getAsLong();
        Verdict current = cached;
        if (current != null && now - current.computedAt() < CACHE_TTL.toNanos()) {
            return current.response();
        }
        // Two concurrent misses may both read the history; both write an
        // equally fresh verdict, which is harmless and needs no lock that a
        // hung database could hold every probe thread on.
        HealthCheckResponse response = readHistory();
        cached = new Verdict(response, now);
        return response;
    }

    /** One actual read of the Flyway history, uncached. */
    HealthCheckResponse readHistory() {
        try {
            var info = flyway.info();
            return evaluate(info.pending(), info.all());
        } catch (RuntimeException e) {
            // The history table could not even be read: not ready, and the
            // reason stays in the log, not in a public response.
            LOG.warnf("Flyway history unreadable, reporting not ready: %s", e.getMessage());
            return HealthCheckResponse.named(NAME).down().build();
        }
    }

    /** Pure verdict, so the rule is tested without a database. */
    static HealthCheckResponse evaluate(MigrationInfo[] pending, MigrationInfo[] all) {
        long failed = Arrays.stream(all).filter(m -> m.getState().isFailed()).count();
        return HealthCheckResponse.named(NAME)
                .status(pending.length == 0 && failed == 0)
                .withData("pending", pending.length)
                .withData("failed", failed)
                .build();
    }
}
