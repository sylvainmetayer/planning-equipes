package dev.sylvain.planning.service.referentiel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.quarkus.test.junit.QuarkusTest;
import java.sql.Array;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;

/**
 * V122 turns {@code stand_horaire.dates} from comma-separated text into a
 * {@code date[]}. It runs once on every database in service, so it is played
 * here on rows written in V37's form: the migrations up to V121 in a schema of
 * their own, the rows, then V122 — the conversion itself, not a copy of it.
 *
 * <p>Every connection here is opened outside the application's pool: Flyway and
 * {@code SET search_path} leave the session pointing at the scratch schema, and
 * a pooled connection handed back in that state breaks every test after this
 * one with "relation does not exist".
 */
@QuarkusTest
class StandHoraireDatesMigrationTest {

    private static final String MIGRATIONS = "filesystem:src/main/resources/db/migration";

    /** The most V37's {@code VARCHAR(512)} could hold: 46 dates and 45 commas. */
    private static final String FORTY_SIX_DATES = IntStream.range(0, 46)
            .mapToObj(i -> LocalDate.of(2026, 7, 1).plusDays(i).toString())
            .collect(Collectors.joining(","));

    @ConfigProperty(name = "quarkus.datasource.jdbc.url")
    String url;

    @ConfigProperty(name = "quarkus.datasource.username")
    String username;

    @ConfigProperty(name = "quarkus.datasource.password")
    String password;

    @Test
    void existingRulesAreConvertedWithoutLoss() throws SQLException {
        String schema = "migration_v122_conversion";
        try {
            migrate(schema, "121");
            Map<Integer, String> stored = new LinkedHashMap<>();
            stored.put(1, "2026-07-14,2026-07-19");
            stored.put(2, FORTY_SIX_DATES);
            // What the old reader tolerated: blanks around a date, an empty piece.
            stored.put(3, " 2026-07-14 , 2026-07-19,");
            stored.put(4, "");
            stored.put(5, null);
            insertRules(schema, stored);

            migrate(schema, "122");

            Map<Integer, List<String>> converted = readRules(schema);
            assertThat(converted.get(1)).containsExactly("2026-07-14", "2026-07-19");
            assertThat(String.join(",", converted.get(2))).isEqualTo(FORTY_SIX_DATES);
            assertThat(converted.get(3)).containsExactly("2026-07-14", "2026-07-19");
            assertThat(converted.get(4)).isNull();
            assertThat(converted.get(5)).isNull();
        } finally {
            drop(schema);
        }
    }

    /** A value that is no date stops the migration rather than vanishing from the rule. */
    @Test
    void anUnreadableDateFailsTheMigration() throws SQLException {
        String schema = "migration_v122_illisible";
        try {
            migrate(schema, "121");
            insertRules(schema, Map.of(1, "2026-07-14,14/07/2026"));

            assertThatThrownBy(() -> migrate(schema, "122")).isInstanceOf(FlywayException.class);
        } finally {
            drop(schema);
        }
    }

    private void migrate(String schema, String target) {
        Flyway.configure()
                .dataSource(url, username, password)
                .schemas(schema)
                .createSchemas(true)
                .locations(MIGRATIONS)
                .target(target)
                .load()
                .migrate();
    }

    private void insertRules(String schema, Map<Integer, String> dates) throws SQLException {
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            statement.execute("SET search_path TO " + schema);
            statement.execute("INSERT INTO edition (id, nom) VALUES ('EMIG', 'Migration')");
            statement.execute("INSERT INTO stand (edition_id, id, nom, effectif_min, effectif_max)"
                    + " VALUES ('EMIG', 'S1', 'Stand', 1, 1)");
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO stand_horaire (edition_id, id, stand_id, mode, type_jours, dates)"
                            + " VALUES ('EMIG', ?, 'S1', 'OUVERTURE', 'DATES', ?)")) {
                for (Map.Entry<Integer, String> rule : dates.entrySet()) {
                    insert.setLong(1, rule.getKey());
                    insert.setString(2, rule.getValue());
                    insert.executeUpdate();
                }
            }
        }
    }

    private Map<Integer, List<String>> readRules(String schema) throws SQLException {
        Map<Integer, List<String>> rules = new LinkedHashMap<>();
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            statement.execute("SET search_path TO " + schema);
            try (ResultSet rs = statement.executeQuery(
                    "SELECT id, dates::text[] AS dates FROM stand_horaire WHERE edition_id = 'EMIG' ORDER BY id")) {
                while (rs.next()) {
                    Array array = rs.getArray("dates");
                    rules.put(
                            rs.getInt("id"),
                            array == null
                                    ? null
                                    : Arrays.stream((Object[]) array.getArray())
                                            .map(String.class::cast)
                                            .toList());
                }
            }
        }
        return rules;
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(url, username, password);
    }

    private void drop(String schema) throws SQLException {
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }
}
