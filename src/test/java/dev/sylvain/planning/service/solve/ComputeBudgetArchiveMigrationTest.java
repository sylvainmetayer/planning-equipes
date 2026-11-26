package dev.sylvain.planning.service.solve;

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
 * V144 takes the solve budget out of {@code parametres_solveur} (it is the
 * instance's now, ADR 0110) and out of the job history's capping columns.
 * Played on a database filled the way 3.x filled it — the migrations up to V142
 * in a schema of their own, then the rows and the V144 file itself in a
 * transaction rolled back afterwards: nothing it removes is lost, the budgets
 * an edition had set move to the archive.
 */
@QuarkusTest
class ComputeBudgetArchiveMigrationTest {

    private static final String SCHEMA = "migration_v144";
    private static final String V144 = "db/migration/V144__budget_de_calcul_d_instance.sql";

    @BeforeAll
    static void migrateUpToV142() throws SQLException {
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
                .target("142")
                .load()
                .migrate();
    }

    @AfterAll
    static void dropSchema() throws SQLException {
        drop();
    }

    @Test
    void theBudgetsAnEditionHadSetMoveToTheArchiveAndTheColumnsGo() throws Exception {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try (Statement sql = connection.createStatement()) {
                sql.execute("SET LOCAL search_path TO " + SCHEMA);
                String first = text(sql, "SELECT min(id) FROM edition");
                sql.execute(
                        "INSERT INTO edition (id, nom) VALUES ('E-SUIT', 'Suit le défaut'), ('E-PLATEAU', 'Plateau seul')");
                sql.execute("INSERT INTO parametres_solveur (edition_id) VALUES ('E-SUIT'), ('E-PLATEAU')");
                sql.execute("UPDATE parametres_solveur SET duree_resolution_secondes = 1200, plateau_secondes = 60,"
                        + " mail_fin_resolution = TRUE WHERE edition_id = '" + first + "'");
                sql.execute("UPDATE parametres_solveur SET plateau_secondes = 0 WHERE edition_id = 'E-PLATEAU'");
                sql.execute("UPDATE parametres_solveur SET duree_resolution_secondes = NULL" + " WHERE edition_id <> '"
                        + first + "'");

                migrate(sql);

                // Only the editions that had set something are archived, with what they set.
                assertThat(rows(
                                sql,
                                "SELECT edition_id || '|' || coalesce(duree_resolution_secondes::text, '-') || '|'"
                                        + " || coalesce(plateau_secondes::text, '-') FROM solve_budget_avant_4_0"
                                        + " ORDER BY edition_id"))
                        .contains(first + "|1200|60", "E-PLATEAU|-|0")
                        .doesNotContain("E-SUIT|-|-");
                // What an edition keeps is its mail switch.
                assertThat(text(
                                sql,
                                "SELECT mail_fin_resolution FROM parametres_solveur WHERE edition_id = '" + first
                                        + "'"))
                        .isEqualTo("t");
                assertThat(text(
                                sql,
                                "SELECT count(*) FROM information_schema.columns WHERE table_schema = '" + SCHEMA
                                        + "' AND ((table_name = 'parametres_solveur' AND column_name IN"
                                        + " ('duree_resolution_secondes', 'plateau_secondes'))"
                                        + " OR (table_name = 'solver_job' AND column_name LIKE 'capped_from%'))"))
                        .isEqualTo("0");

                // The archive goes with its edition, like the row it was copied from.
                sql.execute("DELETE FROM edition WHERE id = 'E-PLATEAU'");
                assertThat(rows(sql, "SELECT edition_id FROM solve_budget_avant_4_0 WHERE edition_id = 'E-PLATEAU'"))
                        .isEmpty();
            } finally {
                connection.rollback();
            }
        }
    }

    private static void migrate(Statement sql) throws Exception {
        try (InputStream file = Thread.currentThread().getContextClassLoader().getResourceAsStream(V144)) {
            assertThat(file).as(V144).isNotNull();
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
