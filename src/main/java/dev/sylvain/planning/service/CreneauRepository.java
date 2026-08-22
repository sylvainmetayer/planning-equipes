package dev.sylvain.planning.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import dev.sylvain.planning.domain.Creneau;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The créneau grid. Its id is a database identity rather than a business
 * string, which is what lets a scenario import renumber a whole grid without
 * ever colliding with another edition's rows.
 */
@ApplicationScoped
public class CreneauRepository {

    @Inject
    DataSource dataSource;

    @Inject
    JdbcEditionScope scope;

    private static final String SELECT_CRENEAU_SQL =
            """
            SELECT c.id, c.date_creneau, c.heure_debut, c.heure_fin, c.famille, c.couverture_pause
            FROM creneau c
            WHERE c.edition_id = ?""";

    public List<Creneau> listCreneaux() {
        return listCreneaux(SELECT_CRENEAU_SQL + " ORDER BY c.id");
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

    public void deleteCreneau(Long id) {
        scope.delete("DELETE FROM creneau WHERE edition_id = ? AND id = ?", id);
    }

    /** Inserts a new timeslot row; the generated id is set back onto {@code creneau} and returned. */
    Long insertCreneauTx(Connection connection, Creneau creneau) throws SQLException {
        try (PreparedStatement ps = scope.prepareScoped(connection,
                """
                INSERT INTO creneau (edition_id, date_creneau, heure_debut, heure_fin, famille, couverture_pause)
                VALUES (?, ?, ?, ?, ?, ?)
                RETURNING id""")) {
            ps.setObject(2, creneau.getDate());
            ps.setObject(3, creneau.getHeureDebut());
            ps.setObject(4, creneau.getHeureFin());
            ps.setInt(5, creneau.getFamille());
            ps.setBoolean(6, creneau.isCouverturePause());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                long id = rs.getLong("id");
                creneau.setId(id);
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
                SET date_creneau = ?, heure_debut = ?, heure_fin = ?, famille = ?, couverture_pause = ?
                WHERE edition_id = ? AND id = ?""")) {
            ps.setObject(1, creneau.getDate());
            ps.setObject(2, creneau.getHeureDebut());
            ps.setObject(3, creneau.getHeureFin());
            ps.setInt(4, creneau.getFamille());
            ps.setBoolean(5, creneau.isCouverturePause());
            ps.setString(6, scope.editionId());
            ps.setLong(7, creneau.getId());
            ps.executeUpdate();
        }
    }
}
