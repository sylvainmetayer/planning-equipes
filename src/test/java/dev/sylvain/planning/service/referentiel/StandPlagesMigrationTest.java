package dev.sylvain.planning.service.referentiel;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.HorairesRecurrents;
import dev.sylvain.planning.scenario.ScenarioYaml;
import dev.sylvain.planning.service.scenario.ScenariosLivres;
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
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * V153 turns the recurring rules of a stand into dated plages. It runs once on
 * every database in service, so it is played here on a database filled the way
 * 3.x filled it: the migrations up to V148 once, in a schema of their own, then
 * for each case the rows and the V153 file itself — not a copy — in a transaction
 * rolled back afterwards.
 *
 * <p>The central rule: the windows a stand opens and closes on, <b>day by day</b>,
 * are exactly the ones 3.x resolved. Every shipped scenario is loaded into the
 * 3.x tables as it was (rules, dated windows, timeslots), converted, and its
 * digest compared with the one 3.x computed ({@code horaires-resolus-avant-4-0.txt},
 * the file {@code ShippedSchedulesDigestTest} also holds the scenario-file link to).</p>
 *
 * <p>Every connection is opened outside the application's pool, for the reason
 * {@code StandHoraireDatesMigrationTest} gives.</p>
 */
@QuarkusTest
class StandPlagesMigrationTest {

    private static final String SCHEMA = "migration_v152";
    private static final String V153 = "db/migration/V153__plages_de_stand.sql";
    private static final Path EMPREINTES = Path.of("src/test/resources/horaires-resolus-avant-4-0.txt");

    @BeforeAll
    static void migrateUpToV149() throws SQLException {
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
                .target("149")
                .load()
                .migrate();
    }

    @AfterAll
    static void dropSchema() throws SQLException {
        drop();
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyShippedScenarioIsResolvedOnEveryDayAsBeforeTheMigration() throws Exception {
        Map<String, String> avant = new LinkedHashMap<>();
        for (String ligne : Files.readAllLines(EMPREINTES, StandardCharsets.UTF_8)) {
            if (!ligne.isBlank() && !ligne.startsWith("#")) {
                avant.put(ligne.split(" ")[0], ligne);
            }
        }
        int compares = 0;
        for (Path scenario : ScenariosLivres.all()) {
            String attendue = avant.get(ScenariosLivres.nom(scenario));
            if (attendue.endsWith(" ERREUR")) {
                continue;
            }
            Map<String, Object> document = (Map<String, Object>)
                    ScenarioYaml.normaliseDates(ScenarioYaml.parser().load(Files.readString(scenario)));
            String apres = inRolledBackTransaction(connection -> {
                load(connection, document);
                migrate(connection);
                return ligne(ScenariosLivres.nom(scenario), connection);
            });
            assertThat(apres).as(scenario.getFileName().toString()).isEqualTo(attendue);
            compares++;
        }
        assertThat(compares).as("scenarios compared").isGreaterThan(40);
    }

    /** The scope that is nearest wins, an opening beats a closure of the same scope, a day nobody names is shut. */
    @Test
    void theSpecificityTheModeAndTheUnnamedDayAreReadAsTheResolverReadThem() throws Exception {
        // 2026-07-08 is a Wednesday.
        Map<String, Object> document = Map.of(
                "creneaux",
                List.of(
                        creneau("2026-07-08", "10:00", "20:00"),
                        creneau("2026-07-11", "10:00", "20:00"),
                        creneau("2026-07-12", "10:00", "20:00")),
                "stands",
                List.of(Map.of(
                        "id",
                        "S",
                        "horaires",
                        List.of(
                                Map.of("mode", "OUVERTURE", "fenetres", List.of(Map.of("heureDebut", "14:00"))),
                                Map.of(
                                        "mode",
                                        "OUVERTURE",
                                        "jours",
                                        "JOURS_SEMAINE",
                                        "joursSemaine",
                                        List.of("SATURDAY"),
                                        "fenetres",
                                        List.of(Map.of("heureDebut", "10:00", "heureFin", "12:00", "effectif", 2))),
                                Map.of(
                                        "mode",
                                        "FERMETURE",
                                        "jours",
                                        "DATES",
                                        "dates",
                                        List.of("2026-07-12"),
                                        "fenetres",
                                        List.of(Map.of("heureDebut", "00:00")))))));

        List<String> plages = inRolledBackTransaction(connection -> {
            load(connection, document);
            migrate(connection);
            return windows(connection, "S");
        });

        assertThat(plages)
                .containsExactlyInAnyOrder(
                        "2026-07-08|O|14:00|null|null", "2026-07-11|O|10:00|12:00|2", "2026-07-12|F|00:00|null|null");
    }

    /** A day carrying a dated window is never touched by a rule; a day without a créneau gets nothing. */
    @Test
    void aDatedWindowKeepsItsDayAndAnUnusedDayGetsNoPlage() throws Exception {
        Map<String, Object> document = Map.of(
                "creneaux",
                List.of(creneau("2026-07-08", "10:00", "20:00"), creneau("2026-07-09", "10:00", "20:00")),
                "stands",
                List.of(Map.of(
                        "id",
                        "S",
                        "horaires",
                        List.of(Map.of(
                                "mode",
                                "OUVERTURE",
                                "jours",
                                "PLAGE",
                                "dateDebut",
                                "2026-07-01",
                                "dateFin",
                                "2026-07-31",
                                "fenetres",
                                List.of(Map.of("heureDebut", "14:00")))),
                        "indisponibilites",
                        List.of(Map.of("date", "2026-07-09", "heureDebut", "10:00", "motif", "Férié")))));

        List<String> plages = inRolledBackTransaction(connection -> {
            load(connection, document);
            migrate(connection);
            return windows(connection, "S");
        });

        assertThat(plages).containsExactlyInAnyOrder("2026-07-08|O|14:00|null|null", "2026-07-09|F|10:00|null|null");
    }

    /** The day after a timeslot crossing midnight is a day to resolve. */
    @Test
    void theMorningAfterANightTimeslotIsResolvedToo() throws Exception {
        Map<String, Object> document = Map.of(
                "creneaux",
                List.of(creneau("2026-07-08", "22:00", "02:00")),
                "stands",
                List.of(Map.of(
                        "id",
                        "S",
                        "horaires",
                        List.of(Map.of("mode", "FERMETURE", "fenetres", List.of(Map.of("heureDebut", "01:00")))))));

        List<String> plages = inRolledBackTransaction(connection -> {
            load(connection, document);
            migrate(connection);
            return windows(connection, "S");
        });

        assertThat(plages).containsExactlyInAnyOrder("2026-07-08|F|01:00|null|null", "2026-07-09|F|01:00|null|null");
    }

    /**
     * The rules stay where they are, the stand's model, whatever the edition: an edition with no timeslot has no
     * day to lay them on and keeps them unconverted, one that has days gets its plages and keeps them too.
     */
    @Test
    void theRulesStayTheModelWhateverTheEdition() throws Exception {
        Map<String, Object> rules = Map.of(
                "id",
                "S",
                "horaires",
                List.of(Map.of("mode", "OUVERTURE", "fenetres", List.of(Map.of("heureDebut", "14:00")))));
        Map<String, Object> withoutDays = Map.of("creneaux", List.of(), "stands", List.of(rules));
        Map<String, Object> dated =
                Map.of("creneaux", List.of(creneau("2026-07-08", "10:00", "20:00")), "stands", List.of(rules));

        int[] before = inRolledBackTransaction(connection -> {
            load(connection, withoutDays);
            migrate(connection);
            return counts(connection);
        });
        int[] after = inRolledBackTransaction(connection -> {
            load(connection, dated);
            migrate(connection);
            return counts(connection);
        });

        // plages, model rules, model windows
        assertThat(before).containsExactly(0, 1, 1);
        assertThat(after).containsExactly(1, 1, 1);
    }

    private static int[] counts(Connection connection) throws SQLException {
        return new int[] {
            count(connection, "stand_ouverture") + count(connection, "stand_indisponibilite"),
            count(connection, "stand_horaire"),
            count(connection, "stand_horaire_fenetre")
        };
    }

    /* --------------------------------- rows --------------------------------- */

    private static Map<String, Object> creneau(String date, String debut, String fin) {
        return Map.of("date", date, "heureDebut", debut, "heureFin", fin);
    }

    @SuppressWarnings("unchecked")
    private static void load(Connection connection, Map<String, Object> document) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO edition (id, nom) VALUES ('EMIG', 'Migration')");
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO creneau (edition_id, date_creneau, heure_debut, heure_fin) VALUES ('EMIG', ?, ?, ?)")) {
            for (Map<String, Object> creneau : maps(document.get("creneaux"))) {
                insert.setObject(1, LocalDate.parse(creneau.get("date").toString()));
                insert.setObject(2, LocalTime.parse(creneau.get("heureDebut").toString()));
                insert.setObject(3, LocalTime.parse(creneau.get("heureFin").toString()));
                insert.addBatch();
            }
            insert.executeBatch();
        }
        for (Map<String, Object> stand : maps(document.get("stands"))) {
            String id = stand.get("id").toString();
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO stand (edition_id, id, nom, effectif_min, effectif_max) VALUES ('EMIG', ?, ?, 1, 4)")) {
                insert.setString(1, id);
                insert.setString(2, id);
                insert.executeUpdate();
            }
            for (Map<String, Object> horaire : maps(stand.get("horaires"))) {
                loadRule(connection, id, horaire);
            }
            for (Map<String, Object> indispo : maps(stand.get("indisponibilites"))) {
                try (PreparedStatement insert = connection.prepareStatement("INSERT INTO stand_indisponibilite "
                        + "(edition_id, stand_id, date_indisponibilite, heure_debut, heure_fin, motif)"
                        + " VALUES ('EMIG', ?, ?, ?, ?, ?)")) {
                    insert.setString(1, id);
                    insert.setObject(2, LocalDate.parse(indispo.get("date").toString()));
                    insert.setObject(3, time(indispo.get("heureDebut")));
                    insert.setObject(4, time(indispo.get("heureFin")));
                    insert.setString(5, text(indispo.get("motif")));
                    insert.executeUpdate();
                }
            }
            for (Map<String, Object> ouverture : maps(stand.get("ouvertures"))) {
                try (PreparedStatement insert = connection.prepareStatement("INSERT INTO stand_ouverture "
                        + "(edition_id, stand_id, date_ouverture, heure_debut, heure_fin, motif, effectif)"
                        + " VALUES ('EMIG', ?, ?, ?, ?, ?, ?)")) {
                    insert.setString(1, id);
                    insert.setObject(2, LocalDate.parse(ouverture.get("date").toString()));
                    insert.setObject(3, time(ouverture.get("heureDebut")));
                    insert.setObject(4, time(ouverture.get("heureFin")));
                    insert.setString(5, text(ouverture.get("motif")));
                    insert.setObject(6, ouverture.get("effectif"));
                    insert.executeUpdate();
                }
            }
        }
    }

    private static void loadRule(Connection connection, String standId, Map<String, Object> horaire)
            throws SQLException {
        long ruleId;
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO stand_horaire (edition_id, stand_id, mode, type_jours, jours_semaine, date_debut,"
                        + " date_fin, dates, motif) VALUES ('EMIG', ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id")) {
            insert.setString(1, standId);
            insert.setString(2, horaire.get("mode").toString());
            insert.setString(
                    3,
                    horaire.get("jours") == null ? "TOUS" : horaire.get("jours").toString());
            insert.setString(
                    4,
                    horaire.get("joursSemaine") == null
                            ? null
                            : String.join(",", strings(horaire.get("joursSemaine"))));
            insert.setObject(
                    5,
                    horaire.get("dateDebut") == null
                            ? null
                            : LocalDate.parse(horaire.get("dateDebut").toString()));
            insert.setObject(
                    6,
                    horaire.get("dateFin") == null
                            ? null
                            : LocalDate.parse(horaire.get("dateFin").toString()));
            List<String> dates = strings(horaire.get("dates"));
            insert.setArray(7, dates.isEmpty() ? null : connection.createArrayOf("date", dates.toArray(String[]::new)));
            insert.setString(8, text(horaire.get("motif")));
            try (ResultSet rs = insert.executeQuery()) {
                rs.next();
                ruleId = rs.getLong(1);
            }
        }
        int position = 0;
        for (Map<String, Object> fenetre : maps(horaire.get("fenetres"))) {
            try (PreparedStatement insert = connection.prepareStatement("INSERT INTO stand_horaire_fenetre "
                    + "(edition_id, horaire_id, position, heure_debut, heure_fin, effectif)"
                    + " VALUES ('EMIG', ?, ?, ?, ?, ?)")) {
                insert.setLong(1, ruleId);
                insert.setInt(2, position++);
                insert.setObject(3, time(fenetre.get("heureDebut")));
                insert.setObject(4, time(fenetre.get("heureFin")));
                insert.setObject(5, fenetre.get("effectif"));
                insert.executeUpdate();
            }
        }
    }

    /** {@code name sha256 stands days windows}, from the rows, in the form {@code ShippedSchedulesDigestTest} writes. */
    private static String ligne(String name, Connection connection) throws SQLException {
        Set<LocalDate> jours = new TreeSet<>();
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "SELECT date_creneau, heure_debut, heure_fin FROM creneau WHERE edition_id = 'EMIG'")) {
            while (rs.next()) {
                jours.addAll(HorairesRecurrents.datesConcernees(
                        rs.getObject(1, LocalDate.class),
                        rs.getObject(2, LocalTime.class),
                        rs.getObject(3, LocalTime.class)));
            }
        }
        List<String> stands = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT id FROM stand WHERE edition_id = 'EMIG'")) {
            while (rs.next()) {
                stands.add(rs.getString(1));
            }
        }
        stands.sort(Comparator.naturalOrder());
        Map<String, List<String>> parStandEtJour = new LinkedHashMap<>();
        for (String stand : stands) {
            for (String fenetre : windows(connection, stand)) {
                String[] morceaux = fenetre.split("\\|", 2);
                parStandEtJour
                        .computeIfAbsent(stand + "|" + morceaux[0], cle -> new ArrayList<>())
                        .add(morceaux[1]);
            }
        }
        StringBuilder texte = new StringBuilder();
        int fenetres = 0;
        for (String stand : stands) {
            for (LocalDate jour : jours) {
                List<String> du = new ArrayList<>(parStandEtJour.getOrDefault(stand + "|" + jour, List.of()));
                du.sort(Comparator.naturalOrder());
                fenetres += du.size();
                texte.append(stand)
                        .append('|')
                        .append(jour)
                        .append('|')
                        .append(du)
                        .append('\n');
            }
        }
        return name + " " + sha256(texte.toString()) + " " + stands.size() + " " + jours.size() + " " + fenetres;
    }

    /** {@code date|O|debut|fin|effectif} and {@code date|F|debut|fin|null} of one stand, as 3.x printed an effective window. */
    private static List<String> windows(Connection connection, String standId) throws SQLException {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT date_ouverture, heure_debut, heure_fin, effectif FROM stand_ouverture"
                        + " WHERE edition_id = 'EMIG' AND stand_id = ?")) {
            ps.setString(1, standId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getObject(1, LocalDate.class) + "|O|" + rs.getObject(2, LocalTime.class) + "|"
                            + rs.getObject(3, LocalTime.class) + "|" + rs.getObject(4, Integer.class));
                }
            }
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT date_indisponibilite, heure_debut, heure_fin FROM stand_indisponibilite"
                        + " WHERE edition_id = 'EMIG' AND stand_id = ?")) {
            ps.setString(1, standId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getObject(1, LocalDate.class) + "|F|" + rs.getObject(2, LocalTime.class) + "|"
                            + rs.getObject(3, LocalTime.class) + "|null");
                }
            }
        }
        return out;
    }

    private static String sha256(String texte) {
        return dev.sylvain.planning.service.scenario.ShippedSchedulesDigestTest.sha256(texte);
    }

    /* -------------------------------- plumbing -------------------------------- */

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    private static List<String> strings(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            list.forEach(item -> out.add(item.toString()));
        }
        return out;
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private static LocalTime time(Object value) {
        return value == null || value.toString().isBlank() ? null : LocalTime.parse(value.toString());
    }

    private static int count(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT count(*) FROM " + table)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static boolean exists(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT to_regclass('" + table + "') IS NOT NULL")) {
            rs.next();
            return rs.getBoolean(1);
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

    private static void migrate(Connection connection) throws IOException, SQLException {
        try (InputStream file = Thread.currentThread().getContextClassLoader().getResourceAsStream(V153);
                Statement statement = connection.createStatement()) {
            assertThat(file).as(V153).isNotNull();
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
