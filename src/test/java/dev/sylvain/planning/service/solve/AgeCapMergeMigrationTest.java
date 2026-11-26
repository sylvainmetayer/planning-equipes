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
 * V152 folds the settings an edition stored for the four rules of 3.x into the
 * two that replace them (ADR 0114), on the most protective reading of each
 * pair: switched on if either was, the hardest level, the strongest weight.
 * Played on a database filled the way 3.x filled it — editions that touched
 * one rule of a pair, both, or neither, a level on one side, rows of other
 * rules that must not move.
 */
@QuarkusTest
class AgeCapMergeMigrationTest {

    private static final String SCHEMA = "migration_v152";
    private static final String V152 = "db/migration/V152__une_regle_par_plafond_et_par_age.sql";

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
        // The last migration before V152, whichever number the others took.
        String before = java.util.Arrays.stream(flyway.load().info().all())
                .map(info -> info.getVersion())
                .filter(version -> version != null && version.compareTo(MigrationVersion.fromVersion("152")) < 0)
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
    void eachPairIsFoldedOnTheMostProtectiveReadingAndNothingElseMoves() throws Exception {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try (Statement sql = connection.createStatement()) {
                sql.execute("SET LOCAL search_path TO " + SCHEMA);
                sql.execute("INSERT INTO edition (id, nom) VALUES ('E-ALL', 'a'), ('E-ONE', 'b'), ('E-MIX', 'c'),"
                        + " ('E-W', 'd'), ('E-L1', 'e'), ('E-L2', 'f'), ('E-L3', 'g'), ('E-L4', 'i')");
                sql.execute(
                        "INSERT INTO constraint_toggle (edition_id, nom, actif) VALUES"
                                // both daily rules off: the merged one is off; one continuous rule off alone: it stays
                                // on
                                + " ('E-ALL', 'dureeQuotidienneMaxMajeur', false), ('E-ALL', 'dureeQuotidienneMaxMineur', false),"
                                + " ('E-ALL', 'travailContinuMaxMajeur', false),"
                                // one off, one explicitly on: on, whichever side
                                + " ('E-ONE', 'travailContinuMaxMajeur', false), ('E-ONE', 'travailContinuMaxMineur', true),"
                                + " ('E-MIX', 'dureeQuotidienneMaxMineur', false), ('E-MIX', 'travailContinuMaxMineur', false),"
                                + " ('E-MIX', 'travailContinuMaxMajeur', false),"
                                // other rules: untouched
                                + " ('E-ALL', 'reposQuotidienMinimal', false), ('E-MIX', 'maxJoursTravaillesParSemaine', true)");
                sql.execute(
                        "INSERT INTO ponderation_contrainte (edition_id, nom, poids, obligatoire) VALUES"
                                // the strongest weight of the two, or the only one
                                + " ('E-W', 'dureeQuotidienneMaxMajeur', 3, NULL), ('E-W', 'dureeQuotidienneMaxMineur', 25, NULL),"
                                + " ('E-W', 'travailContinuMaxMajeur', 7, NULL),"
                                // the hardest level: two preferences stay a preference, with the strongest weight
                                + " ('E-L1', 'dureeQuotidienneMaxMajeur', 5, false), ('E-L1', 'dureeQuotidienneMaxMineur', 2, false),"
                                // one obligatoire among them: obligatoire
                                + " ('E-L2', 'travailContinuMaxMajeur', 5, false), ('E-L2', 'travailContinuMaxMineur', 1, true),"
                                // a preference on one side only: the other side followed the catalogue, which is hard
                                + " ('E-L3', 'dureeQuotidienneMaxMineur', 5, false),"
                                // a level with no weight, a rule not concerned
                                + " ('E-L3', 'travailContinuMaxMajeur', NULL, true), ('E-L3', 'equilibrerCharge', 9, NULL),"
                                // two preferences, one with no weight (5): the age that had 5 keeps 5"
                                + " ('E-L4', 'dureeQuotidienneMaxMajeur', NULL, false), ('E-L4', 'dureeQuotidienneMaxMineur', 1, false)");

                sql.execute(migration());

                assertThat(toggles(sql))
                        .containsExactlyInAnyOrder(
                                "E-ALL|dureeQuotidienneMax|false",
                                "E-ALL|reposQuotidienMinimal|false",
                                "E-MIX|maxJoursTravaillesParSemaine|true",
                                "E-MIX|travailContinuMax|false");
                assertThat(weights(sql))
                        .containsExactlyInAnyOrder(
                                "E-L1|dureeQuotidienneMax|5|false",
                                "E-L2|travailContinuMax|5|true",
                                "E-L3|dureeQuotidienneMax|5|null",
                                "E-L3|equilibrerCharge|9|null",
                                "E-L4|dureeQuotidienneMax|5|false",
                                "E-L3|travailContinuMax|null|true",
                                "E-W|dureeQuotidienneMax|25|null",
                                "E-W|travailContinuMax|7|null");
                String former = "'(dureeQuotidienne|travailContinu)Max(Majeur|Mineur)'";
                assertThat(text(sql, "SELECT count(*) FROM constraint_toggle WHERE nom ~ " + former))
                        .isEqualTo("0");
                assertThat(text(sql, "SELECT count(*) FROM ponderation_contrainte WHERE nom ~ " + former))
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
        try (InputStream file = Thread.currentThread().getContextClassLoader().getResourceAsStream(V152)) {
            assertThat(file).as(V152).isNotNull();
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
