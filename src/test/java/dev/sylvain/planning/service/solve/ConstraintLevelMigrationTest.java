package dev.sylvain.planning.service.solve;

import static org.assertj.core.api.Assertions.assertThat;

import ai.timefold.solver.core.api.domain.solution.ConstraintWeightOverrides;
import ai.timefold.solver.core.api.score.HardMediumSoftScore;
import dev.sylvain.planning.domain.ConstraintToggle;
import dev.sylvain.planning.domain.ParametresQualite;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.service.EmptyReferenceData;
import dev.sylvain.planning.service.diagnostic.ConstraintContribution;
import dev.sylvain.planning.solver.ConstraintCatalog;
import dev.sylvain.planning.solver.ConstraintCatalog.ConstraintDefinition;
import io.quarkus.test.junit.QuarkusTest;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.TreeMap;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * V146 gives every organisation rule a level an edition may choose (ADR 0111).
 * An edition that stored weights and switches before it — every production
 * edition — must score <b>exactly</b> as before: played on a database filled
 * the way 3.x filled it (a weight and a switch on many rules, legal and fixed
 * ones included, a rule the catalogue no longer knows), the migration rewrites
 * no row, and the weights then applied give the same score, rule by rule and
 * level by level, as the 3.x reading of the same rows — under Timefold and
 * under the direct engine.
 */
@QuarkusTest
class ConstraintLevelMigrationTest {

    private static final String SCHEMA = "migration_v146";
    private static final String V146 = "db/migration/V146__niveau_par_regle_et_par_edition.sql";

    /** Varied on purpose: the three positions, the bounds, and weights no position has. */
    private static final int[] WEIGHTS = {3, 1, 25, 7, 500, 5, 2};

    private static final List<String> SCENARIOS = List.of(
            "gamme-05-2j-4stands-8animateurs-mineurs.yaml",
            "gamme-09-4j-6stands-14animateurs-typologies-polyvalente-emplacements.yaml",
            "gamme-13-7j-12stands-30animateurs-premium-epuisants.yaml");

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
        // The last migration before V146, whichever number the others took.
        String before = java.util.Arrays.stream(flyway.load().info().all())
                .map(info -> info.getVersion())
                .filter(version -> version != null && version.compareTo(MigrationVersion.fromVersion("146")) < 0)
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
    void anEditionWithStoredWeightsAndSwitchesScoresExactlyAsBefore() throws Exception {
        Map<String, Integer> weightsBefore;
        Map<String, Boolean> switchesBefore;
        List<String> rowsBefore;
        Map<String, Integer> weightsAfter = new TreeMap<>();
        Map<String, Boolean> levelsAfter = new TreeMap<>();
        Map<String, Boolean> switchesAfter;
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try (Statement sql = connection.createStatement()) {
                sql.execute("SET LOCAL search_path TO " + SCHEMA);
                String edition = text(sql, "SELECT min(id) FROM edition");
                fill(sql, edition);
                weightsBefore = weights(sql, "SELECT nom, poids FROM ponderation_contrainte");
                switchesBefore = switches(sql);
                rowsBefore = rows(sql, "SELECT nom || '|' || poids FROM ponderation_contrainte ORDER BY nom");

                migrate(sql);

                // Not one row rewritten: the weight as it was, no level chosen anywhere.
                assertThat(rows(sql, "SELECT nom || '|' || poids FROM ponderation_contrainte ORDER BY nom"))
                        .isEqualTo(rowsBefore);
                assertThat(text(sql, "SELECT count(*) FROM ponderation_contrainte WHERE obligatoire IS NOT NULL"))
                        .isEqualTo("0");
                switchesAfter = switches(sql);
                assertThat(switchesAfter).isEqualTo(switchesBefore);

                // Read back with the predicates of ParametresRepository.
                weightsAfter.putAll(
                        weights(sql, "SELECT nom, poids FROM ponderation_contrainte WHERE poids IS NOT NULL"));
                try (ResultSet rs = sql.executeQuery(
                        "SELECT nom, obligatoire FROM ponderation_contrainte WHERE obligatoire IS NOT NULL")) {
                    while (rs.next()) {
                        levelsAfter.put(rs.getString(1), rs.getBoolean(2));
                    }
                }
            } finally {
                connection.rollback();
            }
        }
        assertThat(weightsAfter).isEqualTo(weightsBefore);

        SolverConfiguration after = configuration(weightsAfter, levelsAfter, switchesAfter);
        ConstraintWeightOverrides<HardMediumSoftScore> applied = after.constraintWeightOverrides(null, null);
        ConstraintWeightOverrides<HardMediumSoftScore> threeX = threeXReading(weightsBefore);
        for (ConstraintDefinition definition : ConstraintCatalog.definitions()) {
            assertThat(applied.getConstraintWeight(definition.name()))
                    .as(definition.name())
                    .isEqualTo(threeX.getConstraintWeight(definition.name()));
        }

        List<ConstraintToggle> toggles = switchesAfter.entrySet().stream()
                .map(entry -> new ConstraintToggle(entry.getKey(), entry.getValue()))
                .toList();
        for (String scenario : SCENARIOS) {
            HandSearchFixtures.Loaded loaded = HandSearchFixtures.load(scenario);
            PlanningEvenement problem = loaded.problem();
            problem.setConstraintsDesactivees(toggles);
            SplittableRandom random = new SplittableRandom(scenario.hashCode());
            problem.setPonderationsContraintes(applied);
            sameScore(loaded, threeX, applied, scenario + ", empty");
            for (int i = 0; i < 2; i++) {
                HandSearchFixtures.randomPlan(problem, random);
                sameScore(loaded, threeX, applied, scenario + ", random " + i);
            }
            HandSearchFixtures.overloadedPlan(problem);
            sameScore(loaded, threeX, applied, scenario + ", overloaded");
            HandSearchFixtures.emptyPlan(problem);
            HandSearchFixtures.search(problem, 1, 0);
            sameScore(loaded, threeX, applied, scenario + ", searched");
        }
    }

    /**
     * One plan, scored under the 3.x reading of the rows and under the
     * weights applied after V146: the same points on the same level, rule by
     * rule; and the direct engine agreeing with Timefold under the latter.
     */
    private static void sameScore(
            HandSearchFixtures.Loaded loaded,
            ConstraintWeightOverrides<HardMediumSoftScore> threeX,
            ConstraintWeightOverrides<HardMediumSoftScore> applied,
            String plan) {
        PlanningEvenement problem = loaded.problem();
        problem.setPonderationsContraintes(threeX);
        Map<String, HardMediumSoftScore> before = breakdown(loaded);
        HardMediumSoftScore totalBefore =
                loaded.configuration().solutionManager().update(problem);
        problem.setPonderationsContraintes(applied);
        Map<String, HardMediumSoftScore> afterBreakdown = breakdown(loaded);
        HardMediumSoftScore totalAfter =
                loaded.configuration().solutionManager().update(problem);

        assertThat(afterBreakdown)
                .as("Timefold, rule by rule, on the %s plan", plan)
                .isEqualTo(before);
        assertThat(totalAfter).as("Timefold, total, on the %s plan", plan).isEqualTo(totalBefore);
        assertThat(loaded.disagreements())
                .as("direct engine against Timefold on the %s plan, as « engine / constraint provider »", plan)
                .isEmpty();
    }

    private static Map<String, HardMediumSoftScore> breakdown(HandSearchFixtures.Loaded loaded) {
        Map<String, HardMediumSoftScore> scores = new TreeMap<>();
        for (ConstraintContribution contribution : loaded.configuration()
                .diagnosticService()
                .analyze(loaded.problem())
                .contributions()) {
            scores.merge(contribution.constraintName(), contribution.score(), HardMediumSoftScore::add);
        }
        return scores;
    }

    /**
     * What 3.x applied for these rows: the configured weight, replaced by the
     * stored one for a rule the catalogue knows, on the rule's own level, a
     * weight of 1 left out. Written out here rather than read from the code
     * under test, which is what it judges.
     */
    private static ConstraintWeightOverrides<HardMediumSoftScore> threeXReading(Map<String, Integer> stored) {
        Map<String, Integer> configured =
                configuration(Map.of(), Map.of(), Map.of()).effectiveConstraintWeights(Map.of());
        Map<String, HardMediumSoftScore> overrides = new HashMap<>();
        for (ConstraintDefinition definition : ConstraintCatalog.definitions()) {
            int weight = stored.getOrDefault(definition.name(), configured.getOrDefault(definition.name(), 1));
            if (weight == 1) {
                continue;
            }
            overrides.put(
                    definition.name(),
                    switch (definition.niveau()) {
                        case HARD -> HardMediumSoftScore.ofHard(weight);
                        case MEDIUM -> HardMediumSoftScore.ofMedium(weight);
                        case SOFT -> HardMediumSoftScore.ofSoft(weight);
                    });
        }
        return ConstraintWeightOverrides.of(overrides);
    }

    private static SolverConfiguration configuration(
            Map<String, Integer> weights, Map<String, Boolean> levels, Map<String, Boolean> switches) {
        return new SolverConfiguration(
                900L,
                300L,
                ParametresQualite.EMPLACEMENTS_DISTINCTS_PAR_JOUR_MAX_PAR_DEFAUT,
                new EmptyReferenceData() {
                    @Override
                    public Map<String, Integer> getConstraintWeights() {
                        return weights;
                    }

                    @Override
                    public Map<String, Boolean> getNiveauxObligatoires() {
                        return levels;
                    }

                    @Override
                    public Map<String, Boolean> getEtatsContraintes() {
                        return switches;
                    }
                },
                ConfigProvider.getConfig());
    }

    /**
     * A weight on most rules of the catalogue — legal, fixed, organisation and
     * preference alike, the two shipped hard and choosable among them — a
     * switch moved away from its default on one rule in four, the adult beside
     * a minor switched on, and a weight and a switch on a rule since retired.
     */
    private static void fill(Statement sql, String edition) throws SQLException {
        List<ConstraintDefinition> definitions = ConstraintCatalog.definitions();
        List<String> weightRows = new ArrayList<>();
        List<String> switchRows = new ArrayList<>();
        for (int index = 0; index < definitions.size(); index++) {
            ConstraintDefinition definition = definitions.get(index);
            if (index % 5 != 4) {
                weightRows.add(
                        "('" + edition + "', '" + definition.name() + "', " + WEIGHTS[index % WEIGHTS.length] + ")");
            }
            boolean on = definition.name().equals("mineurNecessiteEncadrementMajeur")
                    || (index % 4 == 1 ? !definition.activeByDefault() : definition.activeByDefault());
            if (index % 4 == 1 || definition.name().equals("mineurNecessiteEncadrementMajeur")) {
                switchRows.add("('" + edition + "', '" + definition.name() + "', " + on + ")");
            }
        }
        weightRows.add("('" + edition + "', 'regleRetireeDepuis', 9)");
        switchRows.add("('" + edition + "', 'regleRetireeDepuis', false)");
        sql.execute(
                "INSERT INTO ponderation_contrainte (edition_id, nom, poids) VALUES " + String.join(", ", weightRows));
        sql.execute("INSERT INTO constraint_toggle (edition_id, nom, actif) VALUES " + String.join(", ", switchRows));
    }

    private static Map<String, Integer> weights(Statement sql, String query) throws SQLException {
        Map<String, Integer> weights = new TreeMap<>();
        try (ResultSet rs = sql.executeQuery(query)) {
            while (rs.next()) {
                weights.put(rs.getString(1), rs.getInt(2));
            }
        }
        return weights;
    }

    private static Map<String, Boolean> switches(Statement sql) throws SQLException {
        Map<String, Boolean> switches = new TreeMap<>();
        try (ResultSet rs = sql.executeQuery("SELECT nom, actif FROM constraint_toggle")) {
            while (rs.next()) {
                switches.put(rs.getString(1), rs.getBoolean(2));
            }
        }
        return switches;
    }

    private static void migrate(Statement sql) throws Exception {
        try (InputStream file = Thread.currentThread().getContextClassLoader().getResourceAsStream(V146)) {
            assertThat(file).as(V146).isNotNull();
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
