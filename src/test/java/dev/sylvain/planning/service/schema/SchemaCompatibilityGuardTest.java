package dev.sylvain.planning.service.schema;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The schema guard on the running application: a migration the binary does
 * not ship is written into {@code flyway_schema_history} by hand, and the guard
 * is called again — the boot it protects has already happened, and restarting
 * the application in a test would prove nothing more. Every row added here is
 * removed afterwards, so the rest of the suite reads the history it expects.
 */
@QuarkusTest
class SchemaCompatibilityGuardTest {

    private static final String FUTURE_VERSION = "9999";

    @Inject
    SchemaCompatibilityGuard guard;

    @Inject
    SchemaCompatibilityRepairGuard repairGuard;

    @Inject
    ApplicationVersionRepository versions;

    @Inject
    Flyway flyway;

    @Inject
    DataSource dataSource;

    @ConfigProperty(name = "quarkus.application.version")
    String binaryVersion;

    private long lastVersionLineBefore;

    @BeforeEach
    void rememberTheVersionHistory() throws SQLException {
        lastVersionLineBefore = queryLong("SELECT coalesce(max(id), 0) FROM version_applicative");
    }

    @AfterEach
    void removeWhatTheTestAdded() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM flyway_schema_history WHERE version = '" + FUTURE_VERSION + "'");
            statement.executeUpdate("DELETE FROM version_applicative WHERE id > " + lastVersionLineBefore);
        }
    }

    @Test
    void databaseAtTheBinarysLevelBootsAndKeepsOneLinePerVersion() {
        assertThat(guard.check(false)).isEqualTo(SchemaCompatibility.Outcome.COMPATIBLE);
        guard.recordAdmitted();
        int lines = versions.list().size();

        assertThat(guard.check(false)).isEqualTo(SchemaCompatibility.Outcome.COMPATIBLE);
        guard.recordAdmitted();

        List<ApplicationVersion> history = versions.list();
        assertThat(history).hasSize(lines);
        assertThat(history.getFirst().version()).isEqualTo(binaryVersion);
        assertThat(history.getFirst().latestMigration()).isNotBlank();
    }

    /**
     * Flyway configured as the application configures it lets the future row
     * through validation: the guard is the one to decide, with its message,
     * rather than Flyway with its advice to repair.
     */
    @Test
    void flywayLeavesTheFutureMigrationToTheGuard() throws SQLException {
        insertFutureMigration();

        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
    }

    /** A refused boot leaves no line: the version never opened the database. */
    @Test
    void refusedBootRecordsNothing() throws SQLException {
        assertThat(guard.check(false)).isEqualTo(SchemaCompatibility.Outcome.COMPATIBLE);
        insertFutureMigration();
        int lines = versions.list().size();

        assertThatIllegalStateException().isThrownBy(() -> guard.check(false));
        guard.recordAdmitted();

        assertThat(versions.list()).hasSize(lines);
    }

    @Test
    void databaseAheadOfTheBinaryRefusesTheBoot() throws SQLException {
        insertFutureMigration();

        assertThatIllegalStateException()
                .isThrownBy(() -> guard.check(false))
                .withMessageContaining("La version " + binaryVersion + " de l'application")
                .withMessageContaining("V" + FUTURE_VERSION)
                .withMessageContaining("scripts/restaurer.sh")
                .withMessageContaining("ALLOW_SCHEMA_AHEAD=true");
    }

    /** The version that first started on the future migration is the one the message sends the operator back to. */
    @Test
    void refusalNamesTheVersionThatMigratedTheDatabase() throws SQLException {
        insertFutureMigration();
        versions.recordStart("1.3.0-test", FUTURE_VERSION, Instant.now());

        assertThatIllegalStateException()
                .isThrownBy(() -> guard.check(false))
                .withMessageContaining("appliquée par la version 1.3.0-test")
                .withMessageContaining("APP_VERSION=1.3.0-test");
    }

    @Test
    void escapeHatchBootsWithAWarningAndRecordsTheRollback() throws SQLException {
        insertFutureMigration();
        versions.recordStart("1.3.0-test", FUTURE_VERSION, Instant.now());

        assertThat(guard.check(true)).isEqualTo(SchemaCompatibility.Outcome.WARN);
        guard.recordAdmitted();

        // The older binary opening a database the newer one migrated: the
        // line an incident report reads as « migrated by 1.3.0, then opened
        // by this one ». It names the latest migration the forced binary
        // knows, so that 1.3.0 stays the version that applied the future one.
        List<ApplicationVersion> history = versions.list();
        assertThat(history.get(0).version()).isEqualTo(binaryVersion);
        assertThat(history.get(0).latestMigration())
                .isEqualTo(SchemaCompatibility.assess(flyway.info().all())
                        .latestKnown()
                        .getVersion());
        assertThat(history.get(1).version()).isEqualTo("1.3.0-test");
        assertThat(versions.firstVersionAt(FUTURE_VERSION)).contains("1.3.0-test");
    }

    /**
     * Repair deletes every failed row, a newer binary's included, and runs
     * before the startup observer: the decision has to be taken before it,
     * or the half-migrated schema boots as if nothing had happened.
     */
    @Test
    void repairRefusesToEraseAFailedMigrationOfANewerBinary() throws SQLException {
        insertFutureMigration(false);

        assertThatExceptionOfType(FlywayException.class)
                .isThrownBy(flyway::repair)
                .withMessageContaining("La base est EN AVANCE")
                .withMessageContaining("V" + FUTURE_VERSION);

        assertThat(queryLong("SELECT count(*) FROM flyway_schema_history WHERE version = '" + FUTURE_VERSION
                        + "' AND NOT success"))
                .isOne();
        assertThatIllegalStateException().isThrownBy(() -> guard.check(false));
    }

    @Test
    void escapeHatchLetsTheRepairGoOn() throws SQLException {
        insertFutureMigration(false);

        assertThat(repairGuard.check(flyway.info().all(), true)).isEqualTo(SchemaCompatibility.Outcome.WARN);
    }

    @Test
    void debugPageListsTheVersionsLatestFirst() {
        versions.recordStart("1.3.0-test", "115", Instant.now());

        given().when()
                .get("/api/debug/versions")
                .then()
                .statusCode(200)
                .body("[0].version", equalTo("1.3.0-test"))
                .body("[0].latestMigration", equalTo("115"));
    }

    private void insertFutureMigration() throws SQLException {
        insertFutureMigration(true);
    }

    private void insertFutureMigration(boolean success) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO flyway_schema_history (installed_rank, version, description, type, script,
                                checksum, installed_by, execution_time, success)
                        SELECT coalesce(max(installed_rank), 0) + 1, ?, 'fausse migration future', 'SQL',
                               'V9999__fausse_migration_future.sql', 0, 'test', 0, ?
                        FROM flyway_schema_history""")) {
            statement.setString(1, FUTURE_VERSION);
            statement.setBoolean(2, success);
            statement.executeUpdate();
        }
    }

    private long queryLong(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }
}
