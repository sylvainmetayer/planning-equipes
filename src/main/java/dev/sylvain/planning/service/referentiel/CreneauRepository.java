package dev.sylvain.planning.service.referentiel;

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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import dev.sylvain.planning.domain.Creneau;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import dev.sylvain.planning.service.ConcurrentModificationGuard;
import dev.sylvain.planning.service.JdbcEditionScope;
import dev.sylvain.planning.service.WriteStamp;

/**
 * The créneau grid. Its id is a database identity rather than a business
 * string, which is what lets a scenario import renumber a whole grid without
 * ever colliding with another edition's rows.
 */
@ApplicationScoped
public class CreneauRepository {

    @Inject
    ConcurrentModificationGuard staleWrites;

    @Inject
    DataSource dataSource;

    @Inject
    JdbcEditionScope scope;

    private static final String SELECT_CRENEAU_SQL =
            """
            SELECT c.id, c.date_creneau, c.heure_debut, c.heure_fin, c.famille, c.couverture_pause, c.modifie_le
            FROM creneau c
            WHERE c.edition_id = ?""";

    public List<Creneau> listCreneaux() {
        return listCreneaux(SELECT_CRENEAU_SQL + " ORDER BY c.id");
    }

    /**
     * One timeslot by id, {@code null} when the edition holds none — read for
     * the history's field comparison (issue #406), which would otherwise pay a
     * full grid read per single-row {@code PUT}.
     *
     * <p>The returned {@code jour} is <b>not</b> assigned: that number is
     * computed over the whole grid, and no caller of this method reads it —
     * {@code ChampsModifies} deliberately leaves it out of the comparison.</p>
     */
    public Creneau findCreneau(Long id) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = scope.prepareScoped(connection,
                        """
                        SELECT c.id, c.date_creneau, c.heure_debut, c.heure_fin, c.famille, c.couverture_pause,
                        c.modifie_le
                        FROM creneau c
                        WHERE c.edition_id = ? AND c.id = ?""")) {
            ps.setLong(2, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readCreneau(rs) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read one timeslot", e);
        }
    }

    /**
     * Replaces every créneau of the edition — how the découpage materializes
     * its vacations in place (issue #172: the amplitudes it read are consumed,
     * the edition only ever holds one grid). The persisted plan goes with the
     * créneaux it referenced.
     */
    public void replaceCreneaux(List<Creneau> creneaux) {
        scope.write("Failed to replace timeslots", connection -> {
            try (PreparedStatement ps = scope.prepareScoped(connection,
                    "DELETE FROM poste_affectation WHERE edition_id = ?")) {
                ps.executeUpdate();
            }
            try (PreparedStatement ps = scope.prepareScoped(connection,
                    "DELETE FROM creneau WHERE edition_id = ?")) {
                ps.executeUpdate();
            }
            // A new grid may not have the same families as the old one: the
            // stands' assignments go with the plan (issue #390), and the next
            // build spreads them again.
            try (PreparedStatement ps = scope.prepareScoped(connection,
                    "UPDATE stand SET famille = NULL WHERE edition_id = ?")) {
                ps.executeUpdate();
            }
            for (Creneau creneau : creneaux) {
                creneau.setId(null);
                insertCreneauTx(connection, creneau);
            }
        });
    }

    /**
     * Maps one {@code SELECT_CRENEAU_SQL} row, with {@code jour} left at 0 —
     * it is never stored and is recomputed by {@link Creneau#assignerJours}
     * after loading.
     */
    private static Creneau readCreneau(ResultSet rs) throws SQLException {
        Creneau creneau = new Creneau(
                rs.getLong("id"),
                0,
                rs.getObject("date_creneau", LocalDate.class),
                rs.getObject("heure_debut", LocalTime.class),
                rs.getObject("heure_fin", LocalTime.class));
        creneau.setFamille(rs.getInt("famille"));
        creneau.setCouverturePause(rs.getBoolean("couverture_pause"));
        creneau.setModifieLe(rs.getObject("modifie_le", OffsetDateTime.class).toInstant());
        return creneau;
    }

    private List<Creneau> listCreneaux(String sql) {
        Map<Long, Creneau> byId = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement ps = scope.prepareScoped(connection, sql);
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Creneau creneau = readCreneau(rs);
                    byId.put(creneau.getId(), creneau);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list timeslots", e);
        }
        // `jour` is never stored — it's computed from the edition's earliest
        // date (see Creneau.assignerJours), so consecutive calendar days
        // always yield consecutive day numbers even across a gap day.
        Creneau.assignerJours(byId.values());
        // Chronological order (day, then start time), not `ORDER BY id`: ids like
        // J1.../J10... sort lexicographically ("J10-MATIN" before "J2-MATIN"), and
        // even within one day "APREM"/"MATIN"/"SOIREE" sort alphabetically instead
        // of morning-afternoon-evening. Besides being confusing wherever this list
        // feeds the UI, that scrambled order became the solver's animateurRange-
        // adjacent value order too, and a fixed-seed search is highly sensitive to
        // it — this scenario went from converging in ~25s to stalling short of
        // hard-feasibility within the full 180s production budget.
        List<Creneau> creneaux = new ArrayList<>(byId.values());
        creneaux.sort(Comparator.comparingInt(Creneau::getJour)
                .thenComparing(Creneau::getHeureDebut, Comparator.nullsLast(Comparator.naturalOrder())));
        return creneaux;
    }

    public boolean creneauExists(Long id) {
        return scope.exists("creneau", id);
    }

    /** Inserts a new timeslot; the database generates its id, which is set back onto {@code creneau}. */
    public Creneau insertCreneau(Creneau creneau) {
        return scope.writeAndReturn("Failed to save timeslot", connection -> {
            insertCreneauTx(connection, creneau);
            return creneau;
        });
    }

    /** Updates an existing timeslot in place; its id is left untouched. */
    public void updateCreneau(Creneau creneau) {
        scope.write("Failed to save timeslot " + creneau.getId(), connection -> {
            updateCreneauTx(connection, creneau);
        });
    }

    /**
     * Deletes one timeslot, and the seats that were scheduled on it.
     *
     * <p>A poste cannot outlive its créneau — {@code poste_affectation} is the
     * one table referencing {@code creneau} whose foreign key neither cascades
     * nor nulls out, so until now the delete simply failed as soon as a plan
     * was persisted, and failed as a 500 rather than as a refusal anyone could
     * act on.</p>
     *
     * <p>{@link #replaceCreneaux} already answered the same question for the
     * découpage: "the persisted plan goes with the créneaux it referenced".
     * This is that rule applied to one créneau instead of the whole grid, so
     * only the seats on that slot go and the rest of the plan survives.
     * Destroying them is what the caller asked for — deleting créneaux is
     * documented as destructive and, in bulk, demands an explicit
     * confirmation.</p>
     */
    public void deleteCreneau(Long id) {
        scope.write("Failed to delete timeslot " + id, connection -> {
            try (PreparedStatement ps = scope.prepareScoped(connection,
                    "DELETE FROM poste_affectation WHERE edition_id = ? AND creneau_id = ?")) {
                ps.setLong(2, id);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = scope.prepareScoped(connection,
                    "DELETE FROM creneau WHERE edition_id = ? AND id = ?")) {
                ps.setLong(2, id);
                ps.executeUpdate();
            }
        });
    }

    /** Inserts a new timeslot row; the generated id is set back onto {@code creneau} and returned. */
    Long insertCreneauTx(Connection connection, Creneau creneau) throws SQLException {
        try (PreparedStatement ps = scope.prepareScoped(connection,
                """
                INSERT INTO creneau (edition_id, date_creneau, heure_debut, heure_fin, famille, couverture_pause)
                VALUES (?, ?, ?, ?, ?, ?)
                RETURNING id, modifie_le""")) {
            ps.setObject(2, creneau.getDate());
            ps.setObject(3, creneau.getHeureDebut());
            ps.setObject(4, creneau.getHeureFin());
            ps.setInt(5, creneau.getFamille());
            ps.setBoolean(6, creneau.isCouverturePause());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                long id = rs.getLong("id");
                creneau.setId(id);
                creneau.setModifieLe(rs.getObject("modifie_le", OffsetDateTime.class).toInstant());
            }
        }
        return creneau.getId();
    }

    private void updateCreneauTx(Connection connection, Creneau creneau) throws SQLException {
        // Not prepareScoped: an UPDATE's first placeholder belongs to its SET
        // clause, so the edition predicate can't be the statement's first one.
        try (PreparedStatement ps = connection.prepareStatement(
                """
                UPDATE creneau
                SET date_creneau = ?, heure_debut = ?, heure_fin = ?, famille = ?, couverture_pause = ?,
                modifie_le = now()
                WHERE edition_id = ? AND id = ?
                AND (CAST(? AS timestamptz) IS NULL
                     OR date_trunc('milliseconds', creneau.modifie_le)
                        = date_trunc('milliseconds', CAST(? AS timestamptz)))
                RETURNING modifie_le""")) {
            ps.setObject(1, creneau.getDate());
            ps.setObject(2, creneau.getHeureDebut());
            ps.setObject(3, creneau.getHeureFin());
            ps.setInt(4, creneau.getFamille());
            ps.setBoolean(5, creneau.isCouverturePause());
            ps.setString(6, scope.editionId());
            ps.setLong(7, creneau.getId());
            Timestamp attendu = creneau.getModifieLe() == null ? null : Timestamp.from(creneau.getModifieLe());
            ps.setTimestamp(8, attendu);
            ps.setTimestamp(9, attendu);
            Instant ecrit = WriteStamp.writtenOrRefused(ps);
            if (ecrit == null) {
                staleWrites.refuseStale("creneau", creneau.getId());
            }
            creneau.setModifieLe(ecrit);
        }
    }
}
