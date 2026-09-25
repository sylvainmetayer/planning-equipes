package dev.sylvain.planning.service.referentiel;

import dev.sylvain.planning.domain.TypeVerrouillage;
import dev.sylvain.planning.domain.VerrouillagePlanning;
import dev.sylvain.planning.service.JdbcEditionScope;
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
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;

/** The locks a solve must honour, one nullable target column per {@code TypeVerrouillage}. */
@ApplicationScoped
public class VerrouillageRepository {

    private final DataSource dataSource;

    private final JdbcEditionScope scope;

    @Inject
    public VerrouillageRepository(DataSource dataSource, JdbcEditionScope scope) {
        this.dataSource = dataSource;
        this.scope = scope;
    }

    /**
     * The créneau id is <b>re-resolved</b> on every read, by joining the grid
     * on the natural key the lock stores (issue #577): a créneau deleted and
     * recreated unchanged has a new id, and the lock finds it again instead of
     * having been cascaded away. It comes back {@code null} when the grid no
     * longer holds that vacation — the lock waits rather than disappearing,
     * and the screens say so.
     */
    private static final String SELECT_VERROUILLAGE_SQL = """
            SELECT v.id, v.type, v.animateur_id, v.stand_id, c.id AS creneau_id,
                   v.creneau_date, v.creneau_heure_debut, v.creneau_heure_fin,
                   v.jour, v.raison, v.cree_le
            FROM verrouillage_planning v
            LEFT JOIN creneau c
              ON c.edition_id = v.edition_id
             AND c.date_creneau = v.creneau_date
             AND c.heure_debut = v.creneau_heure_debut
             AND c.heure_fin = v.creneau_heure_fin
            WHERE v.edition_id = ?""";

    /** Every lock of the current edition, most recent first — the ones a solve applies. */
    public List<VerrouillagePlanning> listVerrouillages() {
        List<VerrouillagePlanning> verrouillages = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps =
                        scope.prepareScoped(connection, SELECT_VERROUILLAGE_SQL + " ORDER BY v.cree_le DESC, v.id")) {
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
        VerrouillagePlanning verrouillage =
                new VerrouillagePlanning(rs.getString("id"), TypeVerrouillage.valueOf(rs.getString("type")));
        verrouillage.setAnimateurId(rs.getString("animateur_id"));
        verrouillage.setStandId(rs.getString("stand_id"));
        long creneauId = rs.getLong("creneau_id");
        if (!rs.wasNull()) {
            verrouillage.setCreneauId(creneauId);
        }
        verrouillage.setCreneauDate(rs.getObject("creneau_date", LocalDate.class));
        verrouillage.setCreneauHeureDebut(rs.getObject("creneau_heure_debut", LocalTime.class));
        verrouillage.setCreneauHeureFin(rs.getObject("creneau_heure_fin", LocalTime.class));
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
 INSERT INTO verrouillage_planning (edition_id, id, type, animateur_id, stand_id, creneau_id,
                                    creneau_date, creneau_heure_debut, creneau_heure_fin, jour, raison, cree_le)
 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
 ON CONFLICT DO NOTHING""";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = scope.prepareScoped(connection, sql)) {
            ps.setString(2, verrouillage.getId());
            ps.setString(
                    3, verrouillage.getType() != null ? verrouillage.getType().name() : null);
            ps.setString(4, verrouillage.getAnimateurId());
            ps.setString(5, verrouillage.getStandId());
            ps.setObject(6, verrouillage.getCreneauId());
            ps.setObject(7, verrouillage.getCreneauDate());
            ps.setObject(8, verrouillage.getCreneauHeureDebut());
            ps.setObject(9, verrouillage.getCreneauHeureFin());
            ps.setObject(10, verrouillage.getJour());
            ps.setString(11, verrouillage.getRaison());
            Instant creeLe = verrouillage.getCreeLe() != null ? verrouillage.getCreeLe() : Instant.now();
            ps.setTimestamp(12, Timestamp.from(creeLe));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save planning lock " + verrouillage.getId(), e);
        }
    }

    public void deleteVerrouillage(String id) {
        scope.delete("DELETE FROM verrouillage_planning WHERE edition_id = ? AND id = ?", id);
    }
}
