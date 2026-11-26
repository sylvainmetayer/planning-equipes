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
 * V144 reduces the five lock types to three, each converted into what it froze
 * on the persisted plan. Played on a database filled the way 3.x filled it —
 * the migrations up to V142 in a schema of their own, then the rows and the
 * V144 file itself in a transaction rolled back afterwards.
 */
@QuarkusTest
class LockTypesMigrationTest {

    private static final String SCHEMA = "migration_v144";
    private static final String V144 = "db/migration/V144__trois_types_de_verrou.sql";

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
    void everyRetiredTypeBecomesTheSeatsItFroze() throws Exception {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try (Statement sql = connection.createStatement()) {
                sql.execute("SET LOCAL search_path TO " + SCHEMA);
                sql.execute("INSERT INTO edition (id, nom) VALUES ('EMIG', 'Migration')");
                for (String animateur : List.of("A1", "A2")) {
                    sql.execute("INSERT INTO animateur (edition_id, id, prenom, nom, date_naissance) VALUES"
                            + " ('EMIG', '" + animateur + "', 'P', 'N', DATE '1990-01-01')");
                }
                for (String stand : List.of("S1", "S2")) {
                    sql.execute("INSERT INTO stand (edition_id, id, nom, effectif_min, effectif_max) VALUES"
                            + " ('EMIG', '" + stand + "', 'Stand', 1, 2)");
                }
                // Two days, two timeslots on the first one.
                sql.execute("INSERT INTO creneau (edition_id, date_creneau, heure_debut, heure_fin) VALUES"
                        + " ('EMIG', DATE '2026-07-01', TIME '09:00', TIME '12:00'),"
                        + " ('EMIG', DATE '2026-07-01', TIME '14:00', TIME '17:00'),"
                        + " ('EMIG', DATE '2026-07-02', TIME '09:00', TIME '12:00')");
                String matin =
                        text(sql, "SELECT id FROM creneau WHERE date_creneau = '2026-07-01' AND heure_debut = '09:00'");
                String apresMidi =
                        text(sql, "SELECT id FROM creneau WHERE date_creneau = '2026-07-01' AND heure_debut = '14:00'");
                String lendemain = text(sql, "SELECT id FROM creneau WHERE date_creneau = '2026-07-02'");
                // A1 works S1 on the first morning and the next day; A2 works S2 on the first afternoon.
                sql.execute("INSERT INTO poste_affectation (edition_id, id, stand_id, creneau_id, animateur_id) VALUES"
                        + " ('EMIG', 'P1', 'S1', " + matin + ", 'A1'),"
                        + " ('EMIG', 'P2', 'S1', " + lendemain + ", 'A1'),"
                        + " ('EMIG', 'P3', 'S2', " + apresMidi + ", 'A2'),"
                        + " ('EMIG', 'P4', 'S2', " + matin + ", NULL)");
                sql.execute("INSERT INTO verrouillage_planning (edition_id, id, type, animateur_id) VALUES"
                        + " ('EMIG', 'V1', 'ANIMATEUR', 'A1')");
                sql.execute("INSERT INTO verrouillage_planning (edition_id, id, type, stand_id) VALUES"
                        + " ('EMIG', 'V2', 'STAND', 'S1')");
                sql.execute("INSERT INTO verrouillage_planning (edition_id, id, type, creneau_id, creneau_date,"
                        + " creneau_heure_debut, creneau_heure_fin) VALUES"
                        + " ('EMIG', 'V3', 'CRENEAU', " + apresMidi
                        + ", DATE '2026-07-01', TIME '14:00', TIME '17:00')");
                sql.execute("INSERT INTO verrouillage_planning (edition_id, id, type, jour) VALUES"
                        + " ('EMIG', 'V4', 'JOUR', DATE '2026-07-02')");

                migrate(sql);

                assertThat(rows(
                                sql,
                                "SELECT type || '|' || coalesce(animateur_id, '-') || '|' || coalesce(stand_id, '-')"
                                        + " || '|' || coalesce(creneau_date::text, '-') || '|'"
                                        + " || coalesce(creneau_heure_debut::text, '-') || '|'"
                                        + " || coalesce(jour::text, '-') FROM verrouillage_planning"
                                        + " ORDER BY 1"))
                        .containsExactly(
                                "ANIMATEUR_CRENEAU|A1|-|2026-07-01|09:00:00|-",
                                "ANIMATEUR_CRENEAU|A1|-|2026-07-02|09:00:00|-",
                                "ANIMATEUR_CRENEAU|A2|-|2026-07-01|14:00:00|-",
                                "JOUR|-|-|-|-|2026-07-02",
                                "STAND_JOUR|-|S1|-|-|2026-07-01",
                                "STAND_JOUR|-|S1|-|-|2026-07-02");
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
