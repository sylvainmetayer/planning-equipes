package dev.sylvain.planning.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import dev.sylvain.planning.domain.TypeVerrouillage;
import dev.sylvain.planning.domain.VerrouillagePlanning;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** The locks a solve must honour, one nullable target column per {@code TypeVerrouillage}. */
@ApplicationScoped
public class VerrouillageRepository {

    @Inject
    DataSource dataSource;

    @Inject
    JdbcEditionScope scope;

    private static final String SELECT_VERROUILLAGE_SQL =
            """
            SELECT id, type, animateur_id, stand_id, creneau_id, jour, raison, cree_le
            FROM verrouillage_planning
            WHERE edition_id = ?""";

    /** Every lock of the current edition, most recent first — the ones a solve applies. */
    public List<VerrouillagePlanning> listVerrouillages() {
        List<VerrouillagePlanning> verrouillages = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = scope.prepareScoped(connection,
                        SELECT_VERROUILLAGE_SQL + " ORDER BY cree_le DESC, id")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    verrouillages.add(readVerrouillage(rs));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list planning locks", e);
        }
        return verrouillages;
    }

    private static VerrouillagePlanning readVerrouillage(ResultSet rs) throws SQLException {
        VerrouillagePlanning verrouillage = new VerrouillagePlanning(
                rs.getString("id"), TypeVerrouillage.valueOf(rs.getString("type")));
        verrouillage.setAnimateurId(rs.getString("animateur_id"));
        verrouillage.setStandId(rs.getString("stand_id"));
        long creneauId = rs.getLong("creneau_id");
        if (!rs.wasNull()) {
            verrouillage.setCreneauId(creneauId);
        }
        verrouillage.setJour(rs.getObject("jour", LocalDate.class));
        verrouillage.setRaison(rs.getString("raison"));
        Timestamp creeLe = rs.getTimestamp("cree_le");
        verrouillage.setCreeLe(creeLe != null ? creeLe.toInstant() : null);
        return verrouillage;
    }

    /**
     * Inserts the lock, or does nothing if that exact target is already frozen
     * (see {@code idx_verrouillage_cible_edition}) — locking twice is not an
     * error, it is already locked.
     */
    public void saveVerrouillage(VerrouillagePlanning verrouillage) {
        String sql = """
 INSERT INTO verrouillage_planning (edition_id, id, type, animateur_id, stand_id, creneau_id, jour, raison, cree_le)
 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
 ON CONFLICT DO NOTHING""";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = scope.prepareScoped(connection, sql)) {
            ps.setString(2, verrouillage.getId());
            ps.setString(3, verrouillage.getType() != null ? verrouillage.getType().name() : null);
            ps.setString(4, verrouillage.getAnimateurId());
            ps.setString(5, verrouillage.getStandId());
            ps.setObject(6, verrouillage.getCreneauId());
            ps.setObject(7, verrouillage.getJour());
            ps.setString(8, verrouillage.getRaison());
            Instant creeLe = verrouillage.getCreeLe() != null ? verrouillage.getCreeLe() : Instant.now();
            ps.setTimestamp(9, Timestamp.from(creeLe));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save planning lock " + verrouillage.getId(), e);
        }
    }

    public void deleteVerrouillage(String id) {
        scope.delete("DELETE FROM verrouillage_planning WHERE edition_id = ? AND id = ?", id);
    }
}
