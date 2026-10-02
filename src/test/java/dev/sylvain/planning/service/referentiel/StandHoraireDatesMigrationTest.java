package dev.sylvain.planning.service.referentiel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.quarkus.test.junit.QuarkusTest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * V122 turns {@code stand_horaire.dates} from comma-separated text into a
 * {@code date[]}. It runs once on every database in service, so it is played
 * here on rows written in V37's form: the migrations up to V121 once, in a
 * schema of their own, then for each case the rows and the V122 file itself —
 * not a copy of it — in a transaction rolled back afterwards, which DDL in
 * PostgreSQL allows.
 *
 * <p>Every connection here is opened outside the application's pool: Flyway and
 * {@code search_path} leave the session pointing at the scratch schema, and a
 * pooled connection handed back in that state breaks every test after this one
 * with "relation does not exist".
 */
@QuarkusTest
class StandHoraireDatesMigrationTest {

    private static final String SCHEMA = "migration_v122";

    private static final String V122 = "db/migration/V122__stand_horaire_dates_tableau.sql";

    /** The most V37's {@code VARCHAR(512)} could hold: 46 dates and 45 commas. */
    private static final String FORTY_SIX_DATES = IntStream.range(0, 46)
            .mapToObj(i -> LocalDate.of(2026, 7, 1).plusDays(i).toString())
            .collect(Collectors.joining(","));

    @BeforeAll
    static void migrateUpToV121() throws SQLException {
        // A schema left by an aborted run would already be at V122.
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
                .target("121")
                .load()
                .migrate();
    }

    @AfterAll
    static void dropSchema() throws SQLException {
        drop();
    }

    @Test
    void existingRulesAreConvertedWithoutLoss() throws Exception {
        Map<Integer, String> stored = new LinkedHashMap<>();
        stored.put(1, "2026-07-14,2026-07-19");
        stored.put(2, FORTY_SIX_DATES);
        // What String#trim tolerated: blanks and a tab around a date, an empty piece.
        stored.put(3, " 2026-07-14 ,\t2026-07-19,");
        stored.put(4, "");
        stored.put(5, null);

        Map<Integer, List<String>> converted = inRolledBackTransaction(connection -> {
            insertRules(connection, stored);
            migrateToV122(connection);
            return readRules(connection);
        });

        assertThat(converted.get(1)).containsExactly("2026-07-14", "2026-07-19");
        assertThat(String.join(",", converted.get(2))).isEqualTo(FORTY_SIX_DATES);
        assertThat(converted.get(3)).containsExactly("2026-07-14", "2026-07-19");
        assertThat(converted.get(4)).isNull();
        assertThat(converted.get(5)).isNull();
    }

    /**
     * What {@code LocalDate.parse} refused, or what the date cast would have
     * read as something else: each stops the migration. {@code 14/07/2026} and
     * {@code 07/14/2026} are both here so the case holds on a server reading
     * dates day-first as well as month-first.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "14/07/2026",
                "07/14/2026",
                "today",
                "20260714",
                "Jul 14 2026",
                "infinity",
                "2026-7-4",
                "2026-07 -14",
                "2026-13-01",
                "0000-01-01"
            })
    void anUnreadableDateFailsTheMigration(String unreadable) {
        assertThatThrownBy(() -> inRolledBackTransaction(connection -> {
                    insertRules(connection, Map.of(1, "2026-07-14," + unreadable));
                    migrateToV122(connection);
                    return null;
                }))
                .isInstanceOf(SQLException.class);
    }

    /** What could still reach the column after the migration — a dump replayed, SQL by hand — and not be read back. */
    @ParameterizedTest
    @ValueSource(strings = {"{2026-07-14,NULL}", "{infinity}", "{-infinity}", "{10000-01-01}", "{0001-01-01 BC}"})
    void theColumnRefusesWhatCannotBeReadBack(String array) {
        assertThatThrownBy(() -> inRolledBackTransaction(connection -> {
                    insertRules(connection, Map.of());
                    migrateToV122(connection);
                    try (Statement statement = connection.createStatement()) {
                        statement.execute(
                                "INSERT INTO stand_horaire (edition_id, id, stand_id, mode, type_jours, dates)"
                                        + " VALUES ('EMIG', 1, 'S1', 'OUVERTURE', 'DATES', '" + array + "')");
                    }
                    return null;
                }))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("stand_horaire_dates_lisibles");
    }

    private interface Work<T> {
        T run(Connection connection) throws Exception;
    }

    private static <T> T inRolledBackTransaction(Work<T> work) throws Exception {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET LOCAL search_path TO " + SCHEMA);
            }
            try {
                return work.run(connection);
            } finally {
                connection.rollback();
            }
        }
    }

    private static void migrateToV122(Connection connection) throws IOException, SQLException {
        try (InputStream file = Thread.currentThread().getContextClassLoader().getResourceAsStream(V122);
                Statement statement = connection.createStatement()) {
            assertThat(file).as(V122).isNotNull();
            statement.execute(new String(file.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static void insertRules(Connection connection, Map<Integer, String> dates) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO edition (id, nom) VALUES ('EMIG', 'Migration')");
            statement.execute("INSERT INTO stand (edition_id, id, nom, effectif_min, effectif_max)"
                    + " VALUES ('EMIG', 'S1', 'Stand', 1, 1)");
        }
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

    private static Map<Integer, List<String>> readRules(Connection connection) throws SQLException {
        Map<Integer, List<String>> rules = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
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
        return rules;
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
