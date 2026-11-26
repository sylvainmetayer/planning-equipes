package dev.sylvain.planning.service.referentiel;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * V158 renames the weight history to {@code ponderation_contrainte_historique_avant_4_0}
 * once the action journal took over: the lines are kept as they were, not migrated.
 * Played on the schema as 3.x left it (the migrations up to V152, in a schema of their
 * own), then the V158 file itself in a transaction rolled back afterwards.
 */
@QuarkusTest
class WeightHistoryArchiveMigrationTest {

    private static final String SCHEMA = "migration_v158";
    private static final String V158 = "db/migration/V158__historique_des_ponderations_archive.sql";

    @BeforeAll
    static void migrateUpToV152() throws SQLException {
        drop();
        Config config = ConfigProvider.getConfig();
        Flyway.configure()
                .dataSource(
                        config.getValue("quarkus.datasource.jdbc.url", String.class),
                        config.getValue("quarkus.datasource.username", String.class),
                        config.getValue("quarkus.datasource.password", String.class))
                .schemas(SCHEMA)
                .createSchemas(true)
                .locations("classpath:db/migration")
                .target("152")
                .load()
                .migrate();
    }

    @AfterAll
    static void dropSchema() throws SQLException {
        drop();
    }

    @Test
    void theHistoryKeepsItsLinesUnderTheArchiveNameAndGoesWithItsEdition() throws Exception {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try (Statement sql = connection.createStatement()) {
                sql.execute("SET LOCAL search_path TO " + SCHEMA);
                sql.execute("INSERT INTO edition (id, nom) VALUES ('E-HIST', 'Avec historique')");
                sql.execute("INSERT INTO ponderation_contrainte_historique"
                        + " (edition_id, nom, poids_avant, poids_apres, origine)"
                        + " VALUES ('E-HIST', 'equilibrerCharge', 5, 25, 'ECRAN')");

                try (InputStream file =
                        Thread.currentThread().getContextClassLoader().getResourceAsStream(V158)) {
                    assertThat(file).as(V158).isNotNull();
                    sql.execute(new String(file.readAllBytes(), StandardCharsets.UTF_8));
                }

                assertThat(text(sql, "SELECT to_regclass('ponderation_contrainte_historique')::text"))
                        .isNull();
                assertThat(text(
                                sql,
                                "SELECT nom || '|' || poids_avant || '|' || poids_apres"
                                        + " FROM ponderation_contrainte_historique_avant_4_0"
                                        + " WHERE edition_id = 'E-HIST'"))
                        .isEqualTo("equilibrerCharge|5|25");

                sql.execute("DELETE FROM edition WHERE id = 'E-HIST'");
                assertThat(text(
                                sql,
                                "SELECT count(*) FROM ponderation_contrainte_historique_avant_4_0"
                                        + " WHERE edition_id = 'E-HIST'"))
                        .isEqualTo("0");
            } finally {
                connection.rollback();
            }
        }
    }

    private static String text(Statement sql, String query) throws SQLException {
        try (ResultSet rs = sql.executeQuery(query)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static Connection connect() throws SQLException {
        Config config = ConfigProvider.getConfig();
        return DriverManager.getConnection(
                config.getValue("quarkus.datasource.jdbc.url", String.class),
                config.getValue("quarkus.datasource.username", String.class),
                config.getValue("quarkus.datasource.password", String.class));
    }

    private static void drop() throws SQLException {
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
    }
}
