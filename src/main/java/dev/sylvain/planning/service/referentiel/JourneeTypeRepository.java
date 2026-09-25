package dev.sylvain.planning.service.referentiel;

import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.JourneeType;
import dev.sylvain.planning.domain.VacationType;
import dev.sylvain.planning.service.ConcurrentModificationGuard;
import dev.sylvain.planning.service.JdbcEditionScope;
import dev.sylvain.planning.service.WriteStamp;
import dev.sylvain.planning.service.referentiel.JourneesTypesMaterialisation.Affectation;
import dev.sylvain.planning.service.referentiel.JourneesTypesMaterialisation.Plan;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

/** The day templates, their vacations and the calendar that applies them (V75). */
@ApplicationScoped
public class JourneeTypeRepository {

    private final ConcurrentModificationGuard staleWrites;

    private final DataSource dataSource;

    private final JdbcEditionScope scope;

    private final CreneauRepository creneaux;

    @Inject
    public JourneeTypeRepository(
            ConcurrentModificationGuard staleWrites,
            DataSource dataSource,
            JdbcEditionScope scope,
            CreneauRepository creneaux) {
        this.staleWrites = staleWrites;
        this.dataSource = dataSource;
        this.scope = scope;
        this.creneaux = creneaux;
    }

    private static final String SELECT_JOURNEES_SQL = """
            SELECT j.id, j.nom, j.modifie_le
            FROM journee_type j
            WHERE j.edition_id = ?
            ORDER BY j.id""";

    private static final String SELECT_VACATIONS_SQL = """
            SELECT v.journee_type_id, v.heure_debut, v.heure_fin, v.couverture_pause
            FROM journee_type_vacation v
            WHERE v.edition_id = ?
            ORDER BY v.journee_type_id, v.position, v.id""";

    private static final String SELECT_CALENDRIER_SQL = """
            SELECT d.date_jour, d.journee_type_id
            FROM journee_type_date d
            WHERE d.edition_id = ?
            ORDER BY d.date_jour""";

    private static final String UPDATE_JOURNEE_SQL = """
            UPDATE journee_type
            SET nom = ?, modifie_le = now()
            WHERE edition_id = ? AND id = ?
            AND (CAST(? AS timestamptz) IS NULL
                 OR date_trunc('milliseconds', journee_type.modifie_le)
                    = date_trunc('milliseconds', CAST(? AS timestamptz)))
            RETURNING modifie_le""";

    public List<JourneeType> list() {
        return scope.read("Failed to list day templates", connection -> listTx(connection));
    }

    private List<JourneeType> listTx(Connection connection) throws SQLException {
        Map<Long, JourneeType> parId = new LinkedHashMap<>();
        try (PreparedStatement ps = scope.prepareScoped(connection, SELECT_JOURNEES_SQL);
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                JourneeType journeeType = new JourneeType(rs.getLong("id"), rs.getString("nom"), List.of());
                journeeType.setModifieLe(
                        rs.getObject("modifie_le", OffsetDateTime.class).toInstant());
                parId.put(journeeType.getId(), journeeType);
            }
        }
        try (PreparedStatement ps = scope.prepareScoped(connection, SELECT_VACATIONS_SQL);
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                JourneeType journeeType = parId.get(rs.getLong("journee_type_id"));
                if (journeeType != null) {
                    journeeType
                            .getVacations()
                            .add(new VacationType(
                                    rs.getObject("heure_debut", LocalTime.class),
                                    rs.getObject("heure_fin", LocalTime.class),
                                    rs.getBoolean("couverture_pause")));
                }
            }
        }
        return new ArrayList<>(parId.values());
    }

    public List<Affectation> calendrier() {
        return scope.read("Failed to read the day-template calendar", connection -> calendrierTx(connection));
    }

    private List<Affectation> calendrierTx(Connection connection) throws SQLException {
        List<Affectation> calendrier = new ArrayList<>();
        try (PreparedStatement ps = scope.prepareScoped(connection, SELECT_CALENDRIER_SQL);
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                calendrier.add(
                        new Affectation(rs.getObject("date_jour", LocalDate.class), rs.getLong("journee_type_id")));
            }
        }
        return calendrier;
    }

    public boolean exists(long id) {
        return scope.exists("journee_type", id);
    }

    /** Inserts the template and its vacations; the generated id and stamp are set back onto it. */
    public JourneeType insert(JourneeType journeeType) {
        return scope.writeAndReturn("Failed to save day template", connection -> {
            insertTx(connection, journeeType);
            return journeeType;
        });
    }

    private void insertTx(Connection connection, JourneeType journeeType) throws SQLException {
        try (PreparedStatement ps = scope.prepareScoped(connection, """
                INSERT INTO journee_type (edition_id, nom)
                VALUES (?, ?)
                RETURNING id, modifie_le""")) {
            ps.setString(2, journeeType.getNom());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                journeeType.setId(rs.getLong("id"));
                journeeType.setModifieLe(
                        rs.getObject("modifie_le", OffsetDateTime.class).toInstant());
            }
        }
        insertVacationsTx(connection, journeeType);
    }

    private void insertVacationsTx(Connection connection, JourneeType journeeType) throws SQLException {
        try (PreparedStatement ps = scope.prepareScoped(connection, """
                INSERT INTO journee_type_vacation
                (edition_id, journee_type_id, position, heure_debut, heure_fin, couverture_pause)
                VALUES (?, ?, ?, ?, ?, ?)""")) {
            int position = 0;
            for (VacationType vacation : journeeType.getVacations()) {
                ps.setLong(2, journeeType.getId());
                ps.setInt(3, position++);
                ps.setObject(4, vacation.heureDebut());
                ps.setObject(5, vacation.heureFin());
                ps.setBoolean(6, vacation.couverturePause());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /**
     * Rewrites name and vacations in place, id kept, under the precondition
     * that nobody wrote the template since the caller read it (issue #362).
     */
    public void update(JourneeType journeeType) {
        scope.write("Failed to save day template " + journeeType.getId(), connection -> {
            // Not prepareScoped: an UPDATE's first placeholder belongs to its SET clause.
            try (PreparedStatement ps = connection.prepareStatement(UPDATE_JOURNEE_SQL)) {
                ps.setString(1, journeeType.getNom());
                ps.setString(2, scope.editionId());
                ps.setLong(3, journeeType.getId());
                Timestamp attendu =
                        journeeType.getModifieLe() == null ? null : Timestamp.from(journeeType.getModifieLe());
                ps.setTimestamp(4, attendu);
                ps.setTimestamp(5, attendu);
                Instant ecrit = WriteStamp.writtenOrRefused(ps);
                if (ecrit == null) {
                    staleWrites.refuseStale("journee_type", journeeType.getId());
                }
                journeeType.setModifieLe(ecrit);
            }
            try (PreparedStatement ps = scope.prepareScoped(
                    connection, "DELETE FROM journee_type_vacation WHERE edition_id = ? AND journee_type_id = ?")) {
                ps.setLong(2, journeeType.getId());
                ps.executeUpdate();
            }
            insertVacationsTx(connection, journeeType);
        });
    }

    /** Drops the template; its calendar dates go with it (cascade), the créneaux they produced stay. */
    public void delete(long id) {
        scope.delete("DELETE FROM journee_type WHERE edition_id = ? AND id = ?", id);
    }

    /** The calendar as a whole: every date is rewritten, a date left out is no longer governed. */
    public void replaceCalendrier(List<Affectation> calendrier) {
        scope.write(
                "Failed to write the day-template calendar", connection -> replaceCalendrierTx(connection, calendrier));
    }

    private void replaceCalendrierTx(Connection connection, List<Affectation> calendrier) throws SQLException {
        try (PreparedStatement ps =
                scope.prepareScoped(connection, "DELETE FROM journee_type_date WHERE edition_id = ?")) {
            ps.executeUpdate();
        }
        try (PreparedStatement ps = scope.prepareScoped(connection, """
                INSERT INTO journee_type_date (edition_id, date_jour, journee_type_id)
                VALUES (?, ?, ?)""")) {
            for (Affectation affectation : calendrier) {
                ps.setObject(2, affectation.date());
                ps.setLong(3, affectation.journeeTypeId());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /**
     * Replaces every template and the whole calendar in one transaction — the
     * recognition, and a scenario's own section. Templates arrive with
     * provisional ids (negative, or the file's numbering) that the calendar
     * names; each is inserted, and the calendar rewritten through the mapping.
     */
    public void replaceAll(List<JourneeType> journeesTypes, List<Affectation> calendrier) {
        scope.write("Failed to replace the day templates", connection -> {
            try (PreparedStatement ps =
                    scope.prepareScoped(connection, "DELETE FROM journee_type WHERE edition_id = ?")) {
                ps.executeUpdate();
            }
            Map<Long, Long> remap = new HashMap<>();
            for (JourneeType journeeType : journeesTypes) {
                Long provisoire = journeeType.getId();
                insertTx(connection, journeeType);
                remap.put(provisoire, journeeType.getId());
            }
            List<Affectation> reecrit = new ArrayList<>();
            for (Affectation affectation : calendrier) {
                Long nouvelId = remap.get(affectation.journeeTypeId());
                if (nouvelId == null) {
                    throw new IllegalStateException(
                            "Calendar names a template that was not written: " + affectation.journeeTypeId());
                }
                reecrit.add(new Affectation(affectation.date(), nouvelId));
            }
            replaceCalendrierTx(connection, reecrit);
        });
    }

    /** Seats currently scheduled per créneau id — what a deletion would take with it. */
    public Map<Long, Integer> seatsByCreneau() {
        return scope.read("Failed to count seats per timeslot", connection -> {
            Map<Long, Integer> comptes = new HashMap<>();
            try (PreparedStatement ps = scope.prepareScoped(connection, """
                            SELECT creneau_id, COUNT(*) AS n
                            FROM poste_affectation
                            WHERE edition_id = ?
                            GROUP BY creneau_id""");
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    comptes.put(rs.getLong("creneau_id"), rs.getInt("n"));
                }
            }
            return comptes;
        });
    }

    /**
     * Materialises a plan in one transaction: relay flags flipped in place,
     * missing créneaux inserted, unnamed ones removed with the seats scheduled
     * on them — the same rule {@code CreneauRepository#deleteCreneau} applies
     * to one créneau, here to each of a list.
     */
    public void apply(Plan plan) {
        scope.write("Failed to apply the day templates", connection -> {
            try (PreparedStatement ps = connection.prepareStatement("""
                    UPDATE creneau
                    SET couverture_pause = ?, modifie_le = now()
                    WHERE edition_id = ? AND id = ?""")) {
                for (Creneau creneau : plan.misAJour()) {
                    ps.setBoolean(1, creneau.isCouverturePause());
                    ps.setString(2, scope.editionId());
                    ps.setLong(3, creneau.getId());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            for (Creneau creneau : plan.aCreer()) {
                creneau.setId(null);
                creneaux.insertCreneauTx(connection, creneau);
            }
            try (PreparedStatement postes = scope.prepareScoped(
                            connection, "DELETE FROM poste_affectation WHERE edition_id = ? AND creneau_id = ?");
                    PreparedStatement lignes =
                            scope.prepareScoped(connection, "DELETE FROM creneau WHERE edition_id = ? AND id = ?")) {
                for (Creneau creneau : plan.aSupprimer()) {
                    postes.setLong(2, creneau.getId());
                    postes.addBatch();
                    lignes.setLong(2, creneau.getId());
                    lignes.addBatch();
                }
                postes.executeBatch();
                lignes.executeBatch();
            }
        });
    }
}
