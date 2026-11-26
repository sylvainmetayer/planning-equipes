package dev.sylvain.planning.service.analyse;

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
 * V148 folds {@code kpi_historique} and {@code kpi_realise} into
 * {@code mesure_edition}. Played on a database filled the way 3.x filled it —
 * the migrations up to V145 in a schema of their own, then the rows and the
 * V148 file itself in a transaction rolled back afterwards: not one row is lost,
 * the ids clients know are kept, the realised is still written once, and the
 * old tables stay as archives.
 */
@QuarkusTest
class MesureEditionMigrationTest {

    private static final String SCHEMA = "migration_v148";
    private static final String V148 = "db/migration/V148__mesure_edition.sql";

    @BeforeAll
    static void migrateUpToV145() throws SQLException {
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
                .target("145")
                .load()
                .migrate();
    }

    @AfterAll
    static void dropSchema() throws SQLException {
        drop();
    }

    @Test
    void everyRowOfBothTablesLandsInMesureEditionAndTheOldTablesStayAsArchives() throws Exception {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try (Statement sql = connection.createStatement()) {
                sql.execute("SET LOCAL search_path TO " + SCHEMA);
                // Two editions deleted since: neither table has a key to `edition`.
                sql.execute("INSERT INTO kpi_historique (id, edition_id, edition_nom, kpi, cree_le) VALUES"
                        + " (7, 'E-SUPPRIMEE', 'Année 2024', '{\"postesTotal\": 12}', '2024-07-01T10:00:00Z'),"
                        + " (9, 'E-AUTRE', NULL, 'null', '2025-07-01T10:00:00Z')");
                sql.execute("INSERT INTO kpi_realise (edition_id, typologie_id, edition_nom, typologie_nom,"
                        + " premier_jour, dernier_jour, jours_comptes, sieges_publies, sieges_tenus, absences,"
                        + " remplacements, sieges_vides, sieges_retires, sieges_ajoutes, minutes_publiees,"
                        + " minutes_realisees, minutes_perdues, fige_le) VALUES"
                        + " ('E-SUPPRIMEE', '', 'Année 2024', NULL, '2024-07-10', '2024-07-20', 11, 400, 390, 20,"
                        + " 12, 10, 0, 3, 96000, 94800, 1200, '2024-07-21T01:30:00Z'),"
                        + " ('E-SUPPRIMEE', 'T1', 'Année 2024', 'Jeux', '2024-07-10', '2024-07-20', 11, 100, 95, 5,"
                        + " 3, 2, 0, 1, 24000, 23700, 300, '2024-07-21T01:30:00Z')");
                long avantHistorique = count(sql, "kpi_historique");
                long avantRealise = count(sql, "kpi_realise");

                migrate(sql);

                assertThat(count(sql, "mesure_edition WHERE nature = 'INDICATEURS'"))
                        .isEqualTo(avantHistorique)
                        .isEqualTo(2);
                assertThat(count(sql, "mesure_edition WHERE nature = 'REALISE'"))
                        .isEqualTo(avantRealise)
                        .isEqualTo(2);
                // Nothing lost, nothing duplicated: the archives hold what they held.
                assertThat(count(sql, "kpi_historique_avant_4_0")).isEqualTo(avantHistorique);
                assertThat(count(sql, "kpi_realise_avant_4_0")).isEqualTo(avantRealise);
                assertThat(text(
                                sql,
                                "SELECT count(*) FROM information_schema.tables WHERE table_schema = '" + SCHEMA
                                        + "' AND table_name IN ('kpi_historique', 'kpi_realise')"))
                        .isEqualTo("0");

                // The ids clients know are kept, and the next one comes after them.
                assertThat(text(
                                sql,
                                "SELECT string_agg(id::text, ',' ORDER BY id) FROM mesure_edition"
                                        + " WHERE nature = 'INDICATEURS'"))
                        .isEqualTo("7,9");
                sql.execute("INSERT INTO mesure_edition (nature, edition_id, kpi) VALUES ('INDICATEURS', 'E', '{}')");
                assertThat(text(sql, "SELECT max(id) FROM mesure_edition")).isEqualTo("12");

                // Figures and labels came across, the freeze instant becoming cree_le.
                assertThat(text(
                                sql,
                                "SELECT edition_nom || '|' || sieges_publies || '|' || minutes_perdues || '|'"
                                        + " || dernier_jour || '|' || (cree_le = '2024-07-21T01:30:00Z')"
                                        + " FROM mesure_edition WHERE nature = 'REALISE' AND typologie_id = ''"))
                        .isEqualTo("Année 2024|400|1200|2024-07-20|true");
                assertThat(text(sql, "SELECT kpi->>'postesTotal' FROM mesure_edition WHERE id = 7"))
                        .isEqualTo("12");

                // Written once, still: a second freeze of the same edition and category is a no-op.
                sql.execute("INSERT INTO mesure_edition (nature, edition_id, typologie_id, premier_jour, dernier_jour,"
                        + " jours_comptes, sieges_publies, sieges_tenus, absences, remplacements, sieges_vides,"
                        + " sieges_retires, sieges_ajoutes, minutes_publiees, minutes_realisees, minutes_perdues)"
                        + " VALUES ('REALISE', 'E-SUPPRIMEE', '', '2024-07-10', '2024-07-20', 1, 1, 1, 0, 0, 0, 0, 0,"
                        + " 60, 60, 0) ON CONFLICT (edition_id, typologie_id) WHERE nature = 'REALISE' DO NOTHING");
                assertThat(count(sql, "mesure_edition WHERE nature = 'REALISE'"))
                        .isEqualTo(2);
            } finally {
                connection.rollback();
            }
        }
    }

    private static void migrate(Statement sql) throws Exception {
        try (InputStream file = Thread.currentThread().getContextClassLoader().getResourceAsStream(V148)) {
            assertThat(file).as(V148).isNotNull();
            sql.execute(new String(file.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static long count(Statement sql, String from) throws SQLException {
        return Long.parseLong(text(sql, "SELECT count(*) FROM " + from));
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
