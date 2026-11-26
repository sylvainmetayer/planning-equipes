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
 * V157 folds the hard form of the run of days into the one rule that replaces
 * it (ADR 0118), on the most protective reading: an edition that had switched
 * the hard form on gets the rule obligatoire, switched back on if the dosed form
 * was off, its stored weight kept; any other edition keeps the dosed form's
 * level, weight and toggle. Played on a database filled the way 3.x filled it.
 */
@QuarkusTest
class RunCapMergeMigrationTest {

    private static final String SCHEMA = "migration_v157";
    private static final String V157 = "db/migration/V157__une_seule_regle_de_jours_d_affilee.sql";

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
        // The last migration before V157, whichever number the others took.
        String before = java.util.Arrays.stream(flyway.load().info().all())
                .map(info -> info.getVersion())
                .filter(version -> version != null && version.compareTo(MigrationVersion.fromVersion("157")) < 0)
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
    void theHardFormSwitchedOnMakesTheRuleObligatoireAndNothingElseMoves() throws Exception {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try (Statement sql = connection.createStatement()) {
                sql.execute("SET LOCAL search_path TO " + SCHEMA);
                sql.execute("INSERT INTO edition (id, nom) VALUES ('E-HARD', 'a'), ('E-BOTH', 'b'), ('E-OFF', 'c'),"
                        + " ('E-PREF', 'd'), ('E-NONE', 'e'), ('E-DOSED-OFF', 'f')");
                sql.execute("INSERT INTO constraint_toggle (edition_id, nom, actif) VALUES"
                        // hard form on, nothing stored for the dosed one
                        + " ('E-HARD', 'maxJoursConsecutifsTravaillesDur', true),"
                        // hard form on and the dosed one off: obligatoire, and on again
                        + " ('E-BOTH', 'maxJoursConsecutifsTravaillesDur', true),"
                        + " ('E-BOTH', 'maxJoursConsecutifsTravailles', false),"
                        // hard form explicitly off: the dosed form's settings, untouched
                        + " ('E-OFF', 'maxJoursConsecutifsTravaillesDur', false),"
                        // the dosed form off alone: stays off
                        + " ('E-DOSED-OFF', 'maxJoursConsecutifsTravailles', false),"
                        // another rule: untouched
                        + " ('E-NONE', 'reposQuotidienMinimal', false)");
                sql.execute("INSERT INTO ponderation_contrainte (edition_id, nom, poids, obligatoire) VALUES"
                        // a weight on the dosed form, kept for the way back to a preference
                        + " ('E-HARD', 'maxJoursConsecutifsTravailles', 25, NULL),"
                        // a hard weight on the hard form: gone
                        + " ('E-HARD', 'maxJoursConsecutifsTravaillesDur', 3, NULL),"
                        // the dosed form's weight with the hard form off: kept as it was
                        + " ('E-PREF', 'maxJoursConsecutifsTravailles', 1, NULL),"
                        + " ('E-NONE', 'equilibrerCharge', 9, NULL)");

                sql.execute(migration());

                assertThat(toggles(sql))
                        .containsExactlyInAnyOrder(
                                "E-DOSED-OFF|maxJoursConsecutifsTravailles|false",
                                "E-NONE|reposQuotidienMinimal|false");
                assertThat(weights(sql))
                        .containsExactlyInAnyOrder(
                                "E-BOTH|maxJoursConsecutifsTravailles|null|true",
                                "E-HARD|maxJoursConsecutifsTravailles|25|true",
                                "E-NONE|equilibrerCharge|9|null",
                                "E-PREF|maxJoursConsecutifsTravailles|1|null");
                assertThat(
                                text(
                                        sql,
                                        "SELECT count(*) FROM constraint_toggle WHERE nom = 'maxJoursConsecutifsTravaillesDur'"))
                        .isEqualTo("0");
                assertThat(
                                text(
                                        sql,
                                        "SELECT count(*) FROM ponderation_contrainte WHERE nom = 'maxJoursConsecutifsTravaillesDur'"))
                        .isEqualTo("0");
            } finally {
                connection.rollback();
            }
        }
    }

    private static List<String> toggles(Statement sql) throws SQLException {
        return rows(sql, "SELECT edition_id || '|' || nom || '|' || actif FROM constraint_toggle ORDER BY 1");
    }

    private static List<String> weights(Statement sql) throws SQLException {
        return rows(
                sql,
                "SELECT edition_id || '|' || nom || '|' || coalesce(poids::text, 'null') || '|'"
                        + " || coalesce(obligatoire::text, 'null') FROM ponderation_contrainte ORDER BY 1");
    }

    private static String migration() throws Exception {
        try (InputStream file = Thread.currentThread().getContextClassLoader().getResourceAsStream(V157)) {
            assertThat(file).as(V157).isNotNull();
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
