package dev.sylvain.planning.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.logging.LoggingFilter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.Test;

class DuplicateConnectionGaugeFilterTest {

    private final DuplicateConnectionGaugeFilter filter = new DuplicateConnectionGaugeFilter();

    private static LogRecord record(String logger, String gauge) {
        LogRecord logRecord = new LogRecord(
                Level.WARNING,
                "This Gauge has been already registered (MeterId{name='" + gauge
                        + "', tags=[]}), the registration will be ignored. "
                        + "Note that subsequent logs will be logged at debug level.");
        logRecord.setLoggerName(logger);
        return logRecord;
    }

    @Test
    void dropsTheUpstreamConnectionGaugeWarnings() {
        assertThat(filter.isLoggable(record(DuplicateConnectionGaugeFilter.LOGGER, "vertx.http.connections.current")))
                .isFalse();
        assertThat(filter.isLoggable(record(DuplicateConnectionGaugeFilter.LOGGER, "vertx.http.connections.max")))
                .isFalse();
    }

    @Test
    void keepsADoubleRegistrationOfAnyOtherGauge() {
        assertThat(filter.isLoggable(record(DuplicateConnectionGaugeFilter.LOGGER, "planning.solver.queue.size")))
                .isTrue();
        assertThat(filter.isLoggable(record(DuplicateConnectionGaugeFilter.LOGGER, "http.server.active.connections")))
                .isTrue();
    }

    @Test
    void keepsEveryOtherLogger() {
        assertThat(filter.isLoggable(record("dev.sylvain.planning.Anything", "vertx.http.connections.current")))
                .isTrue();
        assertThat(filter.isLoggable(new LogRecord(Level.INFO, null))).isTrue();
    }

    @Test
    void letsFlywayNewerDatabaseWarningThroughAtStartupOnly() {
        LogRecord warning = new LogRecord(
                Level.WARNING,
                "Using PostgreSQL 19.0 which is newer than the version Flyway has been verified with."
                        + " The latest verified version of PostgreSQL is 18.");
        warning.setLoggerName(DuplicateConnectionGaugeFilter.FLYWAY_LOGGER);
        for (int i = 0; i < DuplicateConnectionGaugeFilter.FLYWAY_STARTUP_WARNINGS; i++) {
            assertThat(filter.isLoggable(warning)).isTrue();
        }
        assertThat(filter.isLoggable(warning)).isFalse();
        assertThat(filter.isLoggable(warning)).isFalse();
        LogRecord other = new LogRecord(Level.WARNING, "Flyway something else");
        other.setLoggerName(DuplicateConnectionGaugeFilter.FLYWAY_LOGGER);
        assertThat(filter.isLoggable(other)).isTrue();
    }

    @Test
    void isWiredToTheConsoleUnderItsOwnName() throws IOException {
        String name = DuplicateConnectionGaugeFilter.class
                .getAnnotation(LoggingFilter.class)
                .name();
        assertThat(Files.readAllLines(Path.of("src/main/resources/application.properties")))
                .contains("quarkus.log.console.filter=" + name);
    }
}
