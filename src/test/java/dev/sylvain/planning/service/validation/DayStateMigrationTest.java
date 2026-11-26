package dev.sylvain.planning.service.validation;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.DayState;
import io.quarkus.test.junit.QuarkusTest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The state of a day is derived from two rows 3.x already wrote (ADR 0121):
 * no migration rewrites them, and none may lose them. Played on a database
 * filled the way 3.x filled it — the migrations up to V150 in a schema of
 * their own, a read day, a day under a day lock, a day carrying both — then
 * every later migration, and the states read back off what is left.
 */
@QuarkusTest
class DayStateMigrationTest {

    private static final String SCHEMA = "migration_etat_journee";

    private static final LocalDate RELU = LocalDate.of(2026, 7, 1);
    private static final LocalDate VERROUILLE = LocalDate.of(2026, 7, 2);
    private static final LocalDate LES_DEUX = LocalDate.of(2026, 7, 3);
    private static final LocalDate RIEN = LocalDate.of(2026, 7, 4);

    @BeforeAll
    static void fillA3xDatabase() throws SQLException {
        drop();
        flyway("150").migrate();
        try (Connection connection = connect();
                Statement sql = connection.createStatement()) {
            sql.execute("SET search_path TO " + SCHEMA);
            sql.execute("INSERT INTO edition (id, nom) VALUES ('EMIG', 'Migration')");
            for (LocalDate jour : List.of(RELU, VERROUILLE, LES_DEUX, RIEN)) {
                sql.execute("INSERT INTO creneau (edition_id, date_creneau, heure_debut, heure_fin) VALUES"
                        + " ('EMIG', DATE '" + jour + "', TIME '09:00', TIME '12:00')");
            }
            for (LocalDate jour : List.of(RELU, LES_DEUX)) {
                sql.execute("INSERT INTO validation_journee (edition_id, id, jour, valide_par, commentaire) VALUES"
                        + " ('EMIG', 'R-" + jour + "', DATE '" + jour + "', 'admin', 'relu')");
            }
            for (LocalDate jour : List.of(VERROUILLE, LES_DEUX)) {
                sql.execute("INSERT INTO verrouillage_planning (edition_id, id, type, jour) VALUES" + " ('EMIG', 'V-"
                        + jour + "', 'JOUR', DATE '" + jour + "')");
            }
        }
    }

    @AfterAll
    static void dropSchema() throws SQLException {
        drop();
    }

    @Test
    void aReadDayIsReadAndADayUnderADayLockIsFrozenWithOrWithoutAReading() throws SQLException {
        flyway(null).migrate();

        try (Connection connection = connect();
                Statement sql = connection.createStatement()) {
            sql.execute("SET search_path TO " + SCHEMA);
            List<LocalDate> read = days(sql, "SELECT jour FROM validation_journee WHERE edition_id = 'EMIG'");
            List<LocalDate> locked =
                    days(sql, "SELECT jour FROM verrouillage_planning WHERE edition_id = 'EMIG' AND type = 'JOUR'");
            Map<LocalDate, DayState> states = new TreeMap<>();
            for (LocalDate jour : List.of(RELU, VERROUILLE, LES_DEUX, RIEN)) {
                states.put(jour, DayState.of(read.contains(jour), locked.contains(jour)));
            }

            assertThat(states)
                    .containsExactlyEntriesOf(new TreeMap<>(Map.of(
                            RELU, DayState.RELUE,
                            VERROUILLE, DayState.FIGEE,
                            LES_DEUX, DayState.FIGEE,
                            RIEN, DayState.A_RELIRE)));
            assertThat(days(sql, "SELECT jour FROM validation_journee WHERE commentaire = 'relu'"))
                    .as("the readings keep their comment")
                    .containsExactlyInAnyOrder(RELU, LES_DEUX);
        }
    }

    private static Flyway flyway(String target) {
        Config config = ConfigProvider.getConfig();
        var configuration = Flyway.configure()
                .dataSource(
                        config.getValue("quarkus.datasource.jdbc.url", String.class),
                        config.getValue("quarkus.datasource.username", String.class),
                        config.getValue("quarkus.datasource.password", String.class))
                .schemas(SCHEMA)
                .createSchemas(true)
                .locations("classpath:db/migration");
        return (target == null ? configuration : configuration.target(target)).load();
    }

    private static List<LocalDate> days(Statement sql, String query) throws SQLException {
        List<LocalDate> days = new ArrayList<>();
        try (ResultSet rs = sql.executeQuery(query)) {
            while (rs.next()) {
                days.add(rs.getObject(1, LocalDate.class));
            }
        }
        return days;
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
