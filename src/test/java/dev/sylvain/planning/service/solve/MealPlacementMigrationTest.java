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
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * V165 adds the meal-break placement to the organisation settings (ADR 0123).
 * An edition that had switched the placement term off says it as « no
 * placement » when it stored its organisation settings, and its silent switch
 * goes; an edition that never stored them keeps its switch, and every other
 * row stays as it was. Played on a database filled the way 4.0 beta filled it.
 */
@QuarkusTest
class MealPlacementMigrationTest {

    private static final String SCHEMA = "migration_v165";
    private static final String V165 = "db/migration/V165__placement_de_la_coupure_repas.sql";

    @BeforeAll
    static void migrateUpToTheMigrationBefore() throws SQLException {
        drop();
        Config config = ConfigProvider.getConfig();
        org.flywaydb.core.api.configuration.FluentConfiguration flyway = org.flywaydb.core.Flyway.configure()
                .dataSource(
                        config.getValue("quarkus.datasource.jdbc.url", String.class),
                        config.getValue("quarkus.datasource.username", String.class),
                        config.getValue("quarkus.datasource.password", String.class))
                .schemas(SCHEMA)
                .createSchemas(true)
                .locations("classpath:db/migration");
        // The last migration before V165, whichever number the others took.
        String before = java.util.Arrays.stream(flyway.load().info().all())
                .map(info -> info.getVersion())
                .filter(version -> version != null && version.compareTo(MigrationVersion.fromVersion("165")) < 0)
                .max(Comparable::compareTo)
                .orElseThrow()
                .getVersion();
        flyway.target(before).load().migrate();
    }

    @AfterAll
    static void dropSchema() throws SQLException {
        drop();
    }

    @Test
    void aSwitchedOffTermBecomesNoPlacementAndEveryoneElseKeepsTheShippedOne() throws Exception {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try (Statement sql = connection.createStatement()) {
                sql.execute("SET LOCAL search_path TO " + SCHEMA);
                sql.execute("INSERT INTO edition (id, nom) VALUES ('E-OFF', 'a'), ('E-ON', 'b'), ('E-NOROW', 'c')");
                sql.execute("INSERT INTO parametres_qualite (edition_id, max_emplacements_distincts_par_jour,"
                        + " repos_souhaite_apres_service_tardif_minutes, typologies_distinctes_max)"
                        + " VALUES ('E-OFF', 3, 720, 2), ('E-ON', 3, 720, 2)");
                sql.execute("INSERT INTO constraint_toggle (edition_id, nom, actif) VALUES"
                        + " ('E-OFF', 'coupureRepasPlacementPrefere', false), ('E-OFF', 'equilibrerCharge', false),"
                        + " ('E-ON', 'equilibrerCharge', false), ('E-NOROW', 'coupureRepasPlacementPrefere', false)");

                sql.execute(migration());

                assertThat(rows(sql, "SELECT edition_id || '|' || placement_repas FROM parametres_qualite ORDER BY 1"))
                        .containsExactly("E-OFF|AUCUN", "E-ON|FIN_MIDI_DEBUT_SOIR");
                assertThat(rows(
                                sql,
                                "SELECT edition_id || '|' || nom || '|' || actif FROM constraint_toggle ORDER BY 1"))
                        .containsExactly(
                                "E-NOROW|coupureRepasPlacementPrefere|false",
                                "E-OFF|equilibrerCharge|false",
                                "E-ON|equilibrerCharge|false");
            } finally {
                connection.rollback();
            }
        }
    }

    private static String migration() throws Exception {
        try (InputStream file = Thread.currentThread().getContextClassLoader().getResourceAsStream(V165)) {
            assertThat(file).as(V165).isNotNull();
            return new String(file.readAllBytes(), StandardCharsets.UTF_8);
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
