package dev.sylvain.planning.service.publication;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * V160 sets aside the people a 3.x publication had been told to leave out: the
 * ones whose last publication line is a report are archived and each gets one
 * alert, by id. Played on a database filled the way 3.x filled it, the file
 * itself in a transaction rolled back afterwards.
 */
@QuarkusTest
class ExclusionArchiveMigrationTest {

    private static final String SCHEMA = "migration_v160";
    private static final String V160 = "db/migration/V160__retrait_exclure_a_la_publication.sql";

    @BeforeAll
    static void migrateUpToV154() throws SQLException {
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
                .target("154")
                .load()
                .migrate();
    }

    @AfterAll
    static void dropSchema() throws SQLException {
        drop();
    }

    @Test
    void theStillDeferredPeopleAreArchivedAndAlertedByIdOnly() throws Exception {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try (Statement sql = connection.createStatement()) {
                sql.execute("SET LOCAL search_path TO " + SCHEMA);
                String edition = text(sql, "SELECT min(id) FROM edition");
                for (String animateur : List.of("A1", "A2", "A3")) {
                    sql.execute("INSERT INTO animateur (edition_id, id, prenom, nom, date_naissance) VALUES ('"
                            + edition + "', '" + animateur + "', 'P', 'N', DATE '1990-01-01')");
                }
                sql.execute("INSERT INTO envoi_planning (edition_id, animateur_id, nature, statut, envoye_le) VALUES"
                        + " ('" + edition + "', 'A1', 'PUBLICATION', 'EXCLU', now()),"
                        + " ('" + edition + "', 'A2', 'PUBLICATION', 'EXCLU', now() - interval '1 day'),"
                        + " ('" + edition + "', 'A2', 'PUBLICATION', 'ENVOYE', now()),"
                        + " ('" + edition + "', 'A3', 'PUBLICATION', 'ENVOYE', now())");

                migrate(sql);

                assertThat(rows(sql, "SELECT animateur_id FROM exclusion_publication_avant_4_0"))
                        .containsExactly("A1");
                assertThat(rows(
                                sql,
                                "SELECT animateur_id || '|' || severite || '|' || cle FROM notification_planifiee"
                                        + " WHERE type = 'EXCLUSION_PUBLICATION_RETIREE'"))
                        .containsExactly("A1|WARNING|A1");
                // The archive goes with the fiche.
                sql.execute("DELETE FROM animateur WHERE id = 'A1'");
                assertThat(rows(sql, "SELECT animateur_id FROM exclusion_publication_avant_4_0"))
                        .isEmpty();
            } finally {
                connection.rollback();
            }
        }
    }

    private static void migrate(Statement sql) throws Exception {
        try (InputStream file = Thread.currentThread().getContextClassLoader().getResourceAsStream(V160)) {
            assertThat(file).as(V160).isNotNull();
            sql.execute(new String(file.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static String text(Statement sql, String query) throws SQLException {
        try (ResultSet rs = sql.executeQuery(query)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static List<String> rows(Statement sql, String query) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (ResultSet rs = sql.executeQuery(query)) {
            while (rs.next()) {
                rows.add(rs.getString(1));
            }
        }
        return rows;
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
