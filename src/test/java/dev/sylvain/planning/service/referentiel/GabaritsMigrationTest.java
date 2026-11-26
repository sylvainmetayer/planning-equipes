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
import java.util.ArrayList;
import java.util.List;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * V169 turns the day templates into the generator's templates (ADR 0126).
 * Played on a database filled the way 3.x filled it — the migrations before
 * V169 in a schema of their own, then the rows and the V169 file itself in a
 * transaction rolled back afterwards: every template that can be applied keeps
 * its id, its vacations and its dates; one that cannot moves to the archive
 * with its dates, and nothing else is lost.
 */
@QuarkusTest
class GabaritsMigrationTest {

    private static final String SCHEMA = "migration_v169";
    private static final String V169 = "db/migration/V169__journees_types_en_gabarits.sql";

    @BeforeAll
    static void migrateUpToV159() throws SQLException {
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
                .target("159")
                .load()
                .migrate();
    }

    @AfterAll
    static void dropSchema() throws SQLException {
        drop();
    }

    @Test
    void theTemplatesBecomeGabaritsAndTheUnappliableOnesMoveToTheArchive() throws Exception {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try (Statement sql = connection.createStatement()) {
                sql.execute("SET LOCAL search_path TO " + SCHEMA);
                String e = "'" + text(sql, "SELECT min(id) FROM edition") + "'";
                sql.execute("INSERT INTO journee_type (edition_id, id, nom) VALUES (" + e + ", 1, 'Jour normal'), (" + e
                        + ", 2, 'Vide'), (" + e + ", 3, 'Nulle'), (" + e + ", 4, 'Double')");
                sql.execute("INSERT INTO journee_type_vacation (edition_id, journee_type_id, position, heure_debut,"
                        + " heure_fin, couverture_pause) VALUES"
                        + " (" + e + ", 1, 0, '09:00', '12:00', false), (" + e + ", 1, 1, '12:00', '13:00', true),"
                        + " (" + e + ", 3, 0, '10:00', '10:00', false),"
                        + " (" + e + ", 4, 0, '14:00', '18:00', false), (" + e + ", 4, 1, '14:00', '18:00', false)");
                sql.execute("INSERT INTO journee_type_date (edition_id, date_jour, journee_type_id) VALUES"
                        + " (" + e + ", '2027-07-01', 1), (" + e + ", '2027-07-02', 1), (" + e + ", '2027-07-03', 2),"
                        + " (" + e + ", '2027-07-04', 4)");

                migrate(sql);

                assertThat(rows(sql, "SELECT id || '|' || nom FROM gabarit ORDER BY id"))
                        .containsExactly("1|Jour normal");
                assertThat(rows(
                                sql,
                                "SELECT heure_debut || '-' || heure_fin || '|' || couverture_pause FROM gabarit_vacation"
                                        + " WHERE gabarit_id = 1 ORDER BY position"))
                        .containsExactly("09:00:00-12:00:00|false", "12:00:00-13:00:00|true");
                assertThat(rows(sql, "SELECT date_jour || '|' || gabarit_id FROM gabarit_date ORDER BY date_jour"))
                        .containsExactly("2027-07-01|1", "2027-07-02|1");
                assertThat(rows(
                                sql,
                                "SELECT id || '|' || nom || '|' || vacations || '|' || dates || '|' || motif"
                                        + " FROM journee_type_avant_4_0 ORDER BY id"))
                        .containsExactly(
                                "2|Vide||2027-07-03|aucune vacation",
                                "3|Nulle|10:00-10:00||vacation de durée nulle",
                                "4|Double|14:00-18:00, 14:00-18:00|2027-07-04|vacation en double");
                assertThat(
                                text(
                                        sql,
                                        "SELECT count(*) FROM information_schema.tables WHERE table_schema = '" + SCHEMA
                                                + "' AND table_name LIKE 'journee_type%' AND table_name <> 'journee_type_avant_4_0'"))
                        .isEqualTo("0");
            } finally {
                connection.rollback();
            }
        }
    }

    private static void migrate(Statement sql) throws Exception {
        try (InputStream file = Thread.currentThread().getContextClassLoader().getResourceAsStream(V169)) {
            assertThat(file).as(V169).isNotNull();
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
