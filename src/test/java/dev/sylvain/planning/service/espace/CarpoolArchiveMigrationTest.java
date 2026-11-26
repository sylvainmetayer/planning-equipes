package dev.sylvain.planning.service.espace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
 * V142 takes the set-aside and cancelled covoiturages out of
 * {@code declaration_coequipier}, and the motif column with them. Played on a
 * database filled the way 3.x filled it — the migrations up to V140 in a schema
 * of their own, then the rows and the V142 file itself in a transaction rolled
 * back afterwards: nothing it removes is lost, it moves to the archive.
 */
@QuarkusTest
class CarpoolArchiveMigrationTest {

    private static final String SCHEMA = "migration_v142";
    private static final String V142 = "db/migration/V142__covoiturage_valide_ou_non.sql";

    @BeforeAll
    static void migrateUpToV140() throws SQLException {
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
                .target("140")
                .load()
                .migrate();
    }

    @AfterAll
    static void dropSchema() throws SQLException {
        drop();
    }

    @Test
    void theSetAsideAndCancelledDemandsMoveToTheArchiveWithTheirMotif() throws Exception {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try (Statement sql = connection.createStatement()) {
                sql.execute("SET LOCAL search_path TO " + SCHEMA);
                String edition = text(sql, "SELECT min(id) FROM edition");
                for (String animateur : List.of("A1", "A2", "A3", "A4")) {
                    sql.execute("INSERT INTO animateur (edition_id, id, prenom, nom, date_naissance) VALUES ('"
                            + edition + "', '" + animateur + "', 'P', 'N', DATE '1990-01-01')");
                }
                sql.execute("INSERT INTO contrainte_ad_hoc (edition_id, id, type) VALUES ('" + edition
                        + "', 'C1', 'ARRIVEE_GROUPEE')");
                sql.execute("INSERT INTO declaration_coequipier (edition_id, id, animateur_id, nature, coequipiers,"
                        + " statut, contrainte_id, decide_le, motif) VALUES"
                        + " ('" + edition + "', 'D1', 'A1', 'COVOITURAGE', 'A2', 'EN_ATTENTE', NULL, NULL, NULL),"
                        + " ('" + edition + "', 'D2', 'A2', 'COVOITURAGE', 'A1', 'VALIDEE', 'C1', now(), NULL),"
                        + " ('" + edition + "', 'D3', 'A3', 'COVOITURAGE', 'A4', 'ECARTEE', NULL, now(),"
                        + " 'Ne vient que le samedi.'),"
                        + " ('" + edition + "', 'D4', 'A4', 'COVOITURAGE', 'A3', 'ANNULEE', NULL, now(), NULL)");

                migrate(sql);

                assertThat(rows(sql, "SELECT id || '|' || statut FROM declaration_coequipier ORDER BY id"))
                        .containsExactly("D1|EN_ATTENTE", "D2|VALIDEE");
                assertThat(text(sql, "SELECT contrainte_id FROM declaration_coequipier WHERE id = 'D2'"))
                        .isEqualTo("C1");
                assertThat(rows(
                                sql,
                                "SELECT id || '|' || statut || '|' || animateur_id || '|' || coequipiers || '|'"
                                        + " || coalesce(motif, '-') FROM declaration_coequipier_avant_4_0 ORDER BY id"))
                        .containsExactly("D3|ECARTEE|A3|A4|Ne vient que le samedi.", "D4|ANNULEE|A4|A3|-");
                assertThat(text(
                                sql,
                                "SELECT count(*) FROM information_schema.columns WHERE table_schema = '" + SCHEMA
                                        + "' AND table_name = 'declaration_coequipier' AND column_name = 'motif'"))
                        .isEqualTo("0");

                // The archive goes with the fiche of the one who asked, like the demand did.
                sql.execute("DELETE FROM animateur WHERE id = 'A3'");
                assertThat(rows(sql, "SELECT id FROM declaration_coequipier_avant_4_0"))
                        .containsExactly("D4");

                assertThatThrownBy(() -> sql.execute("UPDATE declaration_coequipier SET statut = 'ECARTEE'"))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("declaration_coequipier_statut_connu");
            } finally {
                connection.rollback();
            }
        }
    }

    private static void migrate(Statement sql) throws Exception {
        try (InputStream file = Thread.currentThread().getContextClassLoader().getResourceAsStream(V142)) {
            assertThat(file).as(V142).isNotNull();
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
