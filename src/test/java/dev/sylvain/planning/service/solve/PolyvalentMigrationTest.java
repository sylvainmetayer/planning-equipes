package dev.sylvain.planning.service.solve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.scenario.dto.ScenarioDto;
import dev.sylvain.planning.scenario.dto.TypologieDto;
import dev.sylvain.planning.service.scenario.ScenarioYamlReader;
import dev.sylvain.planning.service.scenario.ScenariosLivres;
import dev.sylvain.planning.solver.handsearch.HandSearch;
import io.quarkus.test.junit.QuarkusTest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * V140 renames {@code typologie.ninja} to {@code polyvalent} on every database
 * a 3.x release filled. It is played here on a schema migrated up to V139 — the
 * 3.x shape — and filled with the typologies of every shipped scenario as a 3.x
 * import wrote them, then the V140 file itself, not a copy of it, in a
 * transaction rolled back afterwards.
 *
 * <p>Two things are shown: the flag each typologie carried survives, with the
 * single-holder index and the action journal; and the plan scores alike when
 * the polyvalent typologie is the one read back from the migrated rows rather
 * than from the file — the solver reads nothing else of the rename. The score
 * is taken on a fixed, seeded plan by both engines: a search would only add
 * noise to a comparison whose inputs are the same.</p>
 *
 * <p>Connections are opened outside the application's pool, as in
 * {@code StandHoraireDatesMigrationTest}: a pooled connection handed back with
 * the scratch {@code search_path} would break the tests after this one.</p>
 */
@QuarkusTest
class PolyvalentMigrationTest {

    private static final String SCHEMA = "migration_v140";

    private static final String V140 = "db/migration/V140__ninja_devient_polyvalent.sql";

    @BeforeAll
    static void migrateUpToV139() throws SQLException {
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
                .target("139")
                .load()
                .migrate();
    }

    @AfterAll
    static void dropSchema() throws SQLException {
        drop();
    }

    @Test
    void everyShippedTypologieKeepsItsFlagAcrossV140() throws Exception {
        int withAPolyvalent = 0;
        for (Path scenario : ScenariosLivres.all()) {
            Map<String, Boolean> expected = flagsAsThreeXWroteThem(typologies(scenario));
            if (expected.isEmpty()) {
                continue;
            }
            withAPolyvalent += expected.containsValue(true) ? 1 : 0;
            assertThat(migrated(expected)).as(ScenariosLivres.nom(scenario)).isEqualTo(expected);
        }
        // Not a vacuous loop: the corpus does carry polyvalent typologies.
        assertThat(withAPolyvalent).isGreaterThanOrEqualTo(3);
    }

    @Test
    void theIndexStillAllowsOnePolyvalentPerEditionAndTheJournalSaysPolyvalent() throws Exception {
        inRolledBackTransaction(connection -> {
            insertTypologies(connection, Map.of("A", true, "B", false));
            try (Statement statement = connection.createStatement()) {
                statement.execute("INSERT INTO journal_action (edition_id, id, acteur, action, champs, resultat) VALUES"
                        + " ('EMIG', 1, 'ADMIN', 'X', 'ninja', 'SUCCES'),"
                        + " ('EMIG', 2, 'ADMIN', 'X', 'label,ninja', 'SUCCES'),"
                        + " ('EMIG', 3, 'ADMIN', 'X', 'ninja,code,label', 'SUCCES'),"
                        + " ('EMIG', 4, 'ADMIN', 'X', 'code,label', 'SUCCES'),"
                        + " ('EMIG', 5, 'ADMIN', 'X', NULL, 'SUCCES')");
            }
            runV140(connection);

            List<String> champs = new ArrayList<>();
            try (Statement statement = connection.createStatement();
                    ResultSet rs = statement.executeQuery(
                            "SELECT champs FROM journal_action WHERE edition_id = 'EMIG' ORDER BY id")) {
                while (rs.next()) {
                    champs.add(rs.getString(1));
                }
            }
            assertThat(champs)
                    .containsExactly("polyvalent", "label,polyvalent", "polyvalent,code,label", "code,label", null);

            Savepoint beforeSecond = connection.setSavepoint();
            assertThatThrownBy(() -> {
                        try (Statement statement = connection.createStatement()) {
                            statement.execute("UPDATE typologie SET polyvalent = TRUE WHERE id = 'B'");
                        }
                    })
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("ux_typologie_polyvalent");
            connection.rollback(beforeSecond);
            return null;
        });
    }

    /**
     * The three hand-written or ladder files that hold a polyvalent typologie
     * and are small enough to score twice; each scored once with the
     * typologie its file names, once with the one the migrated rows give back.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "gamme-09-4j-6stands-14animateurs-typologies-polyvalente-emplacements.yaml",
                "scenario-typologies.yaml",
                "festival-hivernal.yaml"
            })
    void thePlanScoresAlikeWithThePolyvalentTypologieReadBackFromTheMigratedRows(String scenario) throws Exception {
        Map<String, Boolean> expected =
                flagsAsThreeXWroteThem(typologies(ScenariosLivres.SOURCE_FOLDER.resolve(scenario)));
        String readBack = migrated(expected).entrySet().stream()
                .filter(Map.Entry::getValue)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
        assertThat(readBack).as("typologie polyvalente relue après V140").isNotNull();

        HandSearchFixtures.Loaded fromFile = seededPlan(scenario, null);
        HandSearchFixtures.Loaded fromDatabase = seededPlan(scenario, readBack);
        assertThat(polyvalents(fromDatabase)).isNotEmpty().isEqualTo(polyvalents(fromFile));
        assertThat(fromDatabase.refereeBreakdown()).isEqualTo(fromFile.refereeBreakdown());
        assertThat(HandSearch.breakdown(fromDatabase.problem())).isEqualTo(HandSearch.breakdown(fromFile.problem()));

        // And the comparison can fail: losing the flag changes the score.
        HandSearchFixtures.Loaded withoutFlag = seededPlan(scenario, "");
        assertThat(withoutFlag.refereeBreakdown()).isNotEqualTo(fromFile.refereeBreakdown());
    }

    /**
     * The scenario, its polyvalent flags recomputed from {@code typologie} when
     * not {@code null} ({@code ""}: no typologie flagged), under a plan drawn
     * from a fixed seed.
     */
    private static HandSearchFixtures.Loaded seededPlan(String scenario, String typologie) {
        HandSearchFixtures.Loaded loaded = HandSearchFixtures.load(scenario);
        if (typologie != null) {
            String id = typologie.isEmpty() ? null : typologie;
            loaded.problem().getAnimateurs().forEach(animateur -> animateur.applyPolyvalentTypologie(id));
        }
        HandSearchFixtures.randomPlan(loaded.problem(), new SplittableRandom(140));
        return loaded;
    }

    private static List<String> polyvalents(HandSearchFixtures.Loaded loaded) {
        return loaded.problem().getAnimateurs().stream()
                .filter(Animateur::isPolyvalent)
                .map(Animateur::getId)
                .toList();
    }

    private static List<TypologieDto> typologies(Path scenario) throws IOException {
        ScenarioDto dto = ScenarioYamlReader.parseScenarioText(Files.readString(scenario, StandardCharsets.UTF_8));
        return dto.typologies() == null ? List.of() : dto.typologies();
    }

    /** What a 3.x import left in {@code typologie.ninja}: the first flagged typologie, the others demoted. */
    private static Map<String, Boolean> flagsAsThreeXWroteThem(List<TypologieDto> typologies) {
        Map<String, Boolean> flags = new LinkedHashMap<>();
        boolean taken = false;
        for (TypologieDto typologie : typologies) {
            boolean flagged = !taken && Boolean.TRUE.equals(typologie.polyvalent());
            taken |= flagged;
            flags.put(typologie.id(), flagged);
        }
        return flags;
    }

    /** The flags written in the 3.x column, as {@code polyvalent} gives them back once V140 has run. */
    private static Map<String, Boolean> migrated(Map<String, Boolean> flags) throws Exception {
        return inRolledBackTransaction(connection -> {
            insertTypologies(connection, flags);
            runV140(connection);
            Map<String, Boolean> read = new LinkedHashMap<>();
            try (Statement statement = connection.createStatement();
                    ResultSet rs =
                            statement.executeQuery("SELECT id, polyvalent FROM typologie WHERE edition_id = 'EMIG'")) {
                while (rs.next()) {
                    read.put(rs.getString("id"), rs.getBoolean("polyvalent"));
                }
            }
            return read;
        });
    }

    private static void insertTypologies(Connection connection, Map<String, Boolean> flags) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO edition (id, nom) VALUES ('EMIG', 'Migration')");
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO typologie (edition_id, id, code, label, ninja) VALUES ('EMIG', ?, ?, ?, ?)")) {
            for (Map.Entry<String, Boolean> typologie : flags.entrySet()) {
                insert.setString(1, typologie.getKey());
                insert.setString(2, typologie.getKey());
                insert.setString(3, typologie.getKey());
                insert.setBoolean(4, typologie.getValue());
                insert.executeUpdate();
            }
        }
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

    private static void runV140(Connection connection) throws IOException, SQLException {
        try (InputStream file = Thread.currentThread().getContextClassLoader().getResourceAsStream(V140);
                Statement statement = connection.createStatement()) {
            assertThat(file).as(V140).isNotNull();
            statement.execute(new String(file.readAllBytes(), StandardCharsets.UTF_8));
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
