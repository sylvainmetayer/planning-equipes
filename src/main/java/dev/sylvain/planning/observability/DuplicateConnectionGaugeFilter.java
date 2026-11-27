package dev.sylvain.planning.observability;

import io.quarkus.logging.LoggingFilter;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Filter;
import java.util.logging.LogRecord;

/**
 * Drops the startup warning Micrometer writes about the two HTTP connection
 * gauges, {@code vertx.http.connections.current} and
 * {@code vertx.http.connections.max}, and nothing else.
 *
 * <p>The cause is upstream, in Quarkus 3.40: with
 * {@code quarkus.http.limits.max-connections} set, every instance of the HTTP
 * server verticle (one per IO thread) registers both gauges again, over the
 * same shared counter and ceiling. Micrometer keeps the first registration and
 * warns about the first duplicate only — whichever of the two the event loops
 * reach first — so the gauges it exposes are the right ones and the warning
 * says nothing an operator can act on. The ceiling stays: dropping it, or running a
 * single IO thread, would cost more than a line in the log.</p>
 *
 * <p>The match names those two gauges only, so a double registration of one of the
 * application's own gauges still warns. Remove this class once Quarkus stops
 * registering them per verticle.</p>
 *
 * <p>It also lets Flyway's "newer than the version Flyway has been verified
 * with" warning through {@link #FLYWAY_STARTUP_WARNINGS} times only. Flyway
 * writes it each time it opens the database, and the readiness probe
 * ({@code FlywayMigrationsReadinessCheck}) does so every time an orchestrator
 * polls it — every 30 s on a database newer than the Flyway release. The ones
 * at startup (the migration, the schema guard) stay; the rest repeat a line
 * that has already told the operator. The probe itself keeps reading the
 * history, which is what it exists for. Console logging takes a single filter,
 * hence one class for both.</p>
 */
@LoggingFilter(name = "duplicate-connection-gauge")
public final class DuplicateConnectionGaugeFilter implements Filter {

    static final String LOGGER = "io.micrometer.core.instrument.MeterRegistry";
    static final String GAUGES = "MeterId{name='vertx.http.connections.";

    static final String FLYWAY_LOGGER = "org.flywaydb.core.internal.database.base.Database";
    static final String FLYWAY_NEWER = "which is newer than the version Flyway has been verified with";
    static final int FLYWAY_STARTUP_WARNINGS = 2;

    private final AtomicInteger flywayWarnings = new AtomicInteger();

    @Override
    public boolean isLoggable(LogRecord logRecord) {
        String message = logRecord.getMessage();
        if (FLYWAY_LOGGER.equals(logRecord.getLoggerName()) && message != null && message.contains(FLYWAY_NEWER)) {
            return flywayWarnings.incrementAndGet() <= FLYWAY_STARTUP_WARNINGS;
        }
        return !(LOGGER.equals(logRecord.getLoggerName())
                && message != null
                && message.contains("already registered")
                && message.contains(GAUGES));
    }
}
