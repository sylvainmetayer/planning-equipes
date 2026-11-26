package dev.sylvain.planning.service.consigne;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.ConsigneEdition;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.ModeHoraire;
import dev.sylvain.planning.domain.PlageStand;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.referentiel.HoraireStandResolver;
import io.quarkus.test.junit.QuarkusTest;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * V168 writes the consignes already laid as plages on the stands (ADR 0125). Played on a database migrated up
 * to the migration before it in a schema of its own, the V168 file itself in a transaction rolled back afterwards: every stand
 * must open, créneau by créneau, exactly where {@link ConsignePlages} — the writer the application lays a new
 * consigne with, itself held to the 3.x layer by {@link ConsigneAsPlagesEquivalenceTest} — opens it.
 *
 * <p>The stands cover the cases the reading has to keep apart: open all day, closed every evening by its own
 * hours and reopened by the consigne, stating its openings at a headcount the window inherits, closed all day
 * by hand.</p>
 */
@QuarkusTest
class ConsignePlagesMigrationTest {

    private static final String SCHEMA = "migration_v168";
    private static final String V168 = "db/migration/V168__consigne_en_plages.sql";

    private static final LocalDate J1 = LocalDate.of(2026, 7, 8);
    private static final LocalDate J2 = J1.plusDays(1);

    @BeforeAll
    static void migrateUpToV167() throws SQLException {
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
                .target(versionBefore168())
                .load()
                .migrate();
    }

    /** The last migration before V168: the numbers between are reserved, and whichever landed is the base. */
    private static String versionBefore168() {
        try (var fichiers = java.nio.file.Files.list(java.nio.file.Path.of("src/main/resources/db/migration"))) {
            return String.valueOf(fichiers.map(path -> path.getFileName().toString())
                    .filter(nom -> nom.matches("V\\d+__.*\\.sql"))
                    .mapToInt(nom -> Integer.parseInt(nom.substring(1, nom.indexOf("__"))))
                    .filter(version -> version < 168)
                    .max()
                    .orElseThrow());
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @AfterAll
    static void dropSchema() throws SQLException {
        drop();
    }

    @Test
    void theConsignesLaidBeforeOpenEveryStandAsTheWriterDoes() throws Exception {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET LOCAL search_path TO " + SCHEMA);
            }
            try {
                check(connection);
            } finally {
                connection.rollback();
            }
        }
    }

    private static void check(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO edition (id, nom) VALUES ('EMIG', 'Migration')");
        }
        List<Creneau> grille = new ArrayList<>();
        for (LocalDate jour : List.of(J1, J2)) {
            grille.add(creneau(connection, jour, 10, 12));
            grille.add(creneau(connection, jour, 12, 14));
            grille.add(creneau(connection, jour, 14, 20));
        }
        Creneau soir = creneau(connection, J1, 20, 22);
        Creneau matin = creneau(connection, J2, 8, 10);
        grille.add(soir);
        grille.add(matin);

        Map<String, Stand> stands = new LinkedHashMap<>();
        stands.put("OUVERT", stand(connection, "OUVERT", 1));
        stands.put("SOIR", stand(connection, "SOIR", 1));
        plage(connection, stands.get("SOIR"), PlageStand.fermeture(null, J1, LocalTime.of(17, 0), null, "Soir"));
        stands.put("DECLARE", stand(connection, "DECLARE", 1));
        for (LocalDate jour : List.of(J1, J2)) {
            plage(connection, stands.get("DECLARE"), PlageStand.ouverture(null, jour, at(10), at(12), null, 3));
            plage(connection, stands.get("DECLARE"), PlageStand.ouverture(null, jour, at(14), at(20), null, 3));
        }
        stands.put("FERME", stand(connection, "FERME", 1));
        plage(connection, stands.get("FERME"), PlageStand.fermeture(null, J1, LocalTime.MIDNIGHT, null, "Panne"));

        List<ConsigneEdition> consignes = List.of(
                consigne(
                        connection,
                        J1,
                        at(12),
                        at(18),
                        List.of(
                                new ConsigneEdition.Ouverture("OUVERT", at(18), at(22), null),
                                new ConsigneEdition.Ouverture("SOIR", at(18), at(22), 2),
                                new ConsigneEdition.Ouverture("DECLARE", at(16), at(22), null),
                                new ConsigneEdition.Ouverture("FERME", at(18), at(22), null)),
                        soir),
                consigne(
                        connection,
                        J2,
                        at(14),
                        null,
                        List.of(new ConsigneEdition.Ouverture("DECLARE", at(8), at(15), 2)),
                        matin));

        // What the writer lays, on the stands as they stand before the migration.
        List<Stand> attendus = new ArrayList<>(stands.values());
        List<Map<String, List<PlageStand>>> ecrites = new ArrayList<>();
        for (ConsigneEdition consigne : consignes) {
            ecrites.add(ConsignePlages.of(attendus, consigne, grille));
        }
        for (Map<String, List<PlageStand>> parStand : ecrites) {
            attendus.forEach(stand -> stand.getPlages().addAll(parStand.get(stand.getId())));
        }
        HoraireStandResolver.apply(attendus, grille);

        try (InputStream file = Thread.currentThread().getContextClassLoader().getResourceAsStream(V168);
                Statement statement = connection.createStatement()) {
            assertThat(file).as(V168).isNotNull();
            statement.execute(new String(file.readAllBytes(), StandardCharsets.UTF_8));
        }

        List<Stand> migres = read(connection, stands);
        HoraireStandResolver.apply(migres, grille);
        int compares = 0;
        for (int i = 0; i < attendus.size(); i++) {
            for (Creneau creneau : grille) {
                assertThat(creneau.segmentsOuverts(migres.get(i)))
                        .as(attendus.get(i).getId() + " on " + creneau.getDate() + " " + creneau.getHeureDebut())
                        .isEqualTo(creneau.segmentsOuverts(attendus.get(i)));
                compares++;
            }
        }
        assertThat(compares).isEqualTo(32);
        // The reopened evening, the stand's own closure notwithstanding — and only the migration's rows are marked.
        assertThat(soir.segmentsOuverts(migres.get(1))).isNotEmpty();
        assertThat(migres.get(1).getPlages())
                .filteredOn(plage -> !plage.isConsigne())
                .extracting(PlageStand::getMotif)
                .containsExactly("Soir");
    }

    /* --------------------------------- rows --------------------------------- */

    private static LocalTime at(int heure) {
        return LocalTime.of(heure, 0);
    }

    private static Creneau creneau(Connection connection, LocalDate jour, int debut, int fin) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO creneau (edition_id, date_creneau,"
                + " heure_debut, heure_fin) VALUES ('EMIG', ?, ?, ?) RETURNING id")) {
            insert.setObject(1, jour);
            insert.setObject(2, at(debut));
            insert.setObject(3, at(fin));
            try (ResultSet rs = insert.executeQuery()) {
                rs.next();
                return new Creneau(rs.getLong(1), 0, jour, at(debut), at(fin));
            }
        }
    }

    private static Stand stand(Connection connection, String id, int effectifMin) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO stand (edition_id, id, nom, effectif_min, effectif_max) VALUES ('EMIG', ?, ?, ?, 4)")) {
            insert.setString(1, id);
            insert.setString(2, id);
            insert.setInt(3, effectifMin);
            insert.executeUpdate();
        }
        return new Stand(id, id, Set.of(), effectifMin, 4, false);
    }

    private static void plage(Connection connection, Stand stand, PlageStand plage) throws SQLException {
        boolean fermeture = plage.getMode() == ModeHoraire.FERMETURE;
        try (PreparedStatement insert = connection.prepareStatement(
                fermeture
                        ? "INSERT INTO stand_indisponibilite (edition_id, stand_id, date_indisponibilite, heure_debut,"
                                + " heure_fin, motif) VALUES ('EMIG', ?, ?, ?, ?, ?)"
                        : "INSERT INTO stand_ouverture (edition_id, stand_id, date_ouverture, heure_debut, heure_fin, motif,"
                                + " effectif) VALUES ('EMIG', ?, ?, ?, ?, ?, ?)")) {
            insert.setString(1, stand.getId());
            insert.setObject(2, plage.getDate());
            insert.setObject(3, plage.getHeureDebut());
            insert.setObject(4, plage.getHeureFin());
            insert.setString(5, plage.getMotif());
            if (!fermeture) {
                insert.setObject(6, plage.getEffectif());
            }
            insert.executeUpdate();
        }
        stand.getPlages().add(plage);
    }

    private static ConsigneEdition consigne(
            Connection connection,
            LocalDate date,
            LocalTime debut,
            LocalTime fin,
            List<ConsigneEdition.Ouverture> ouvertures,
            Creneau ajoute)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO consigne_edition (edition_id,"
                + " date_jour, fermeture_debut, fermeture_fin, motif) VALUES ('EMIG', ?, ?, ?, 'Arrêté')")) {
            insert.setObject(1, date);
            insert.setObject(2, debut);
            insert.setObject(3, fin);
            insert.executeUpdate();
        }
        int position = 0;
        for (ConsigneEdition.Ouverture ouverture : ouvertures) {
            try (PreparedStatement insert = connection.prepareStatement("INSERT INTO consigne_edition_ouverture"
                    + " (edition_id, date_jour, stand_id, position, heure_debut, heure_fin, effectif)"
                    + " VALUES ('EMIG', ?, ?, ?, ?, ?, ?)")) {
                insert.setObject(1, date);
                insert.setString(2, ouverture.standId());
                insert.setInt(3, position++);
                insert.setObject(4, ouverture.debut());
                insert.setObject(5, ouverture.fin());
                insert.setObject(6, ouverture.effectif());
                insert.executeUpdate();
            }
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO consigne_edition_creneau (edition_id, date_jour, creneau_id) VALUES ('EMIG', ?, ?)")) {
            insert.setObject(1, date);
            insert.setLong(2, ajoute.getId());
            insert.executeUpdate();
        }
        return new ConsigneEdition(
                date, debut, fin, "Arrêté", null, List.of(), ouvertures, List.of(ajoute.getId()), null, null, null);
    }

    /** The stands again, from the tables, the marks included. */
    private static List<Stand> read(Connection connection, Map<String, Stand> stands) throws SQLException {
        Map<String, Stand> lus = new LinkedHashMap<>();
        stands.values()
                .forEach(stand -> lus.put(
                        stand.getId(),
                        new Stand(stand.getId(), stand.getId(), Set.of(), stand.getEffectifMin(), 4, false)));
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT stand_id, date_indisponibilite, heure_debut, heure_fin,"
                        + " motif, consigne FROM stand_indisponibilite ORDER BY id")) {
            while (rs.next()) {
                PlageStand plage = PlageStand.fermeture(
                        null,
                        rs.getObject(2, LocalDate.class),
                        rs.getObject(3, LocalTime.class),
                        rs.getObject(4, LocalTime.class),
                        rs.getString(5));
                plage.setConsigne(rs.getBoolean(6));
                lus.get(rs.getString(1)).getPlages().add(plage);
            }
        }
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT stand_id, date_ouverture, heure_debut, heure_fin, motif,"
                        + " effectif, consigne FROM stand_ouverture ORDER BY id")) {
            while (rs.next()) {
                PlageStand plage = PlageStand.ouverture(
                        null,
                        rs.getObject(2, LocalDate.class),
                        rs.getObject(3, LocalTime.class),
                        rs.getObject(4, LocalTime.class),
                        rs.getString(5),
                        rs.getObject(6, Integer.class));
                plage.setConsigne(rs.getBoolean(7));
                lus.get(rs.getString(1)).getPlages().add(plage);
            }
        }
        return new ArrayList<>(lus.values());
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
