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
 * V156 retires the forced unavailability and the forced assignment (ADR 0117).
 * Played on a database filled the way 3.x filled it — the migrations up to V152
 * in a schema of their own, then the rows and the V156 file itself in a
 * transaction rolled back afterwards: the exceptions go to the archive, become
 * days off and locks where they can, and the plan is not touched.
 */
@QuarkusTest
class AdHocTypesMigrationTest {

    private static final String SCHEMA = "migration_v156";
    private static final String V156 = "db/migration/V156__deux_types_d_exception_ad_hoc.sql";

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
    void theRetiredExceptionsBecomeDaysOffAndLocksWithoutTouchingThePlan() throws Exception {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try (Statement sql = connection.createStatement()) {
                sql.execute("SET LOCAL search_path TO " + SCHEMA);
                String edition = text(sql, "SELECT min(id) FROM edition");
                for (String animateur : List.of("A1", "A2", "A3", "A4", "A5")) {
                    sql.execute("INSERT INTO animateur (edition_id, id, prenom, nom, date_naissance) VALUES ('"
                            + edition + "', '" + animateur + "', 'P', 'N', DATE '1990-01-01')");
                }
                sql.execute("INSERT INTO stand (edition_id, id, nom, effectif_min, effectif_max)" + " VALUES ('"
                        + edition + "', 'MIG-S1', 'Stand', 1, 2)");
                sql.execute("INSERT INTO creneau (edition_id, id, date_creneau, heure_debut, heure_fin) VALUES"
                        + " ('" + edition + "', 1, DATE '2026-07-01', TIME '09:00', TIME '12:00'),"
                        + " ('" + edition + "', 2, DATE '2026-07-01', TIME '14:00', TIME '17:00'),"
                        + " ('" + edition + "', 3, DATE '2026-07-02', TIME '09:00', TIME '12:00')");
                sql.execute("INSERT INTO poste_affectation (edition_id, id, stand_id, creneau_id, animateur_id) VALUES"
                        + " ('" + edition + "', 'P1', 'MIG-S1', 1, 'A1'),"
                        + " ('" + edition + "', 'P2', 'MIG-S1', 2, 'A2'),"
                        + " ('" + edition + "', 'P3', 'MIG-S1', 3, NULL)");
                String morning = "DATE '2026-07-01', TIME '09:00', TIME '12:00'";
                // Forced unavailabilities: on a timeslot, on none, on a stand.
                exception(sql, edition, "I1", "INDISPONIBILITE_FORCEE", morning, null, "A3");
                exception(sql, edition, "I2", "INDISPONIBILITE_FORCEE", "NULL, NULL, NULL", null, "A4");
                exception(sql, edition, "I3", "INDISPONIBILITE_FORCEE", morning, "MIG-S1", "A5");
                // Forced assignments: held, and not held.
                exception(sql, edition, "F1", "AFFECTATION_FORCEE", morning, null, "A1");
                exception(sql, edition, "F2", "AFFECTATION_FORCEE", morning, null, "A2");
                exception(sql, edition, "K1", "INCOMPATIBILITE", "NULL, NULL, NULL", null, "A1");
                sql.execute("INSERT INTO constraint_toggle (edition_id, nom, actif) VALUES"
                        + " ('" + edition + "', 'indisponibiliteForcee', FALSE),"
                        + " ('" + edition + "', 'affectationForcee', FALSE),"
                        + " ('" + edition + "', 'incompatibiliteAdHoc', FALSE)");
                sql.execute("INSERT INTO ponderation_contrainte (edition_id, nom, poids) VALUES"
                        + " ('" + edition + "', 'indisponibiliteForcee', 3),"
                        + " ('" + edition + "', 'affectationForcee', 3),"
                        + " ('" + edition + "', 'incompatibiliteAdHoc', 3)");
                String planBefore = planRows(sql);
                assertThat(planBefore.split("\n")).hasSize(3);

                migrate(sql);

                assertThat(planRows(sql)).isEqualTo(planBefore);
                assertThat(rows(
                                sql,
                                "SELECT animateur_id || '|' || jour FROM animateur_jour_indispo"
                                        + " ORDER BY animateur_id, jour"))
                        .containsExactly("A3|2026-07-01", "A4|2026-07-01", "A4|2026-07-02");
                assertThat(rows(
                                sql,
                                "SELECT type || '|' || animateur_id || '|' || creneau_id || '|' || creneau_date"
                                        + " FROM verrouillage_planning"))
                        .containsExactly("ANIMATEUR_CRENEAU|A1|1|2026-07-01");
                assertThat(rows(
                                sql,
                                "SELECT id || '|' || type || '|' || animateur_ids || '|' || convertie"
                                        + " FROM contrainte_ad_hoc_avant_4_0 ORDER BY id"))
                        .containsExactly(
                                "F1|AFFECTATION_FORCEE|A1|true",
                                "F2|AFFECTATION_FORCEE|A2|false",
                                "I1|INDISPONIBILITE_FORCEE|A3|true",
                                "I2|INDISPONIBILITE_FORCEE|A4|true",
                                "I3|INDISPONIBILITE_FORCEE|A5|false");
                assertThat(rows(sql, "SELECT id || '|' || type FROM contrainte_ad_hoc"))
                        .containsExactly("K1|INCOMPATIBILITE");
                assertThat(rows(sql, "SELECT nom FROM constraint_toggle")).containsExactly("incompatibiliteAdHoc");
                assertThat(rows(sql, "SELECT nom FROM ponderation_contrainte")).containsExactly("incompatibiliteAdHoc");
            } finally {
                connection.rollback();
            }
        }
    }

    private static void exception(
            Statement sql, String edition, String id, String type, String window, String stand, String animateur)
            throws SQLException {
        sql.execute("INSERT INTO contrainte_ad_hoc (edition_id, id, type, creneau_date, creneau_heure_debut,"
                + " creneau_heure_fin, stand_id) VALUES ('" + edition + "', '" + id + "', '" + type + "', " + window
                + ", " + (stand == null ? "NULL" : "'" + stand + "'") + ")");
        sql.execute("INSERT INTO contrainte_animateur (edition_id, contrainte_id, animateur_id, position) VALUES ('"
                + edition + "', '" + id + "', '" + animateur + "', 0)");
    }

    private static String planRows(Statement sql) throws SQLException {
        return String.join(
                "\n",
                rows(
                        sql,
                        "SELECT id || '|' || stand_id || '|' || creneau_id || '|' || coalesce(animateur_id, '-')"
                                + " FROM poste_affectation ORDER BY id"));
    }

    private static void migrate(Statement sql) throws Exception {
        try (InputStream file = Thread.currentThread().getContextClassLoader().getResourceAsStream(V156)) {
            assertThat(file).as(V156).isNotNull();
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
