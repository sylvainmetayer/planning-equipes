package dev.sylvain.planning.observability;

import io.quarkus.logging.LoggingFilter;
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
 */
@LoggingFilter(name = "duplicate-connection-gauge")
public final class DuplicateConnectionGaugeFilter implements Filter {

    static final String LOGGER = "io.micrometer.core.instrument.MeterRegistry";
    static final String GAUGES = "MeterId{name='vertx.http.connections.";

    @Override
    public boolean isLoggable(LogRecord logRecord) {
        String message = logRecord.getMessage();
        return !(LOGGER.equals(logRecord.getLoggerName())
                && message != null
                && message.contains("already registered")
                && message.contains(GAUGES));
    }
}
