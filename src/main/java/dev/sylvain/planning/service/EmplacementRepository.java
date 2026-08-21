package dev.sylvain.planning.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import dev.sylvain.planning.domain.Emplacement;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** The emplacement rows stands are pinned to — a flat referential, and the only one with coordinates. */
@ApplicationScoped
public class EmplacementRepository {

    @Inject
    DataSource dataSource;

    @Inject
    JdbcEditionScope scope;

    public List<Emplacement> listEmplacements() {
        List<Emplacement> emplacements = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = scope.prepareScoped(connection,
                        "SELECT id, nom, latitude, longitude FROM emplacement WHERE edition_id = ? ORDER BY id");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                emplacements.add(new Emplacement(rs.getString("id"), rs.getString("nom"),
                        (Double) rs.getObject("latitude"), (Double) rs.getObject("longitude")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list emplacements", e);
        }
        return emplacements;
    }

    public boolean emplacementExists(String id) {
        return scope.existe("emplacement", id);
    }

    public void saveEmplacement(Emplacement emplacement) {
        try (Connection connection = dataSource.getConnection()) {
            upsertEmplacementTx(connection, emplacement);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save emplacement " + emplacement.getId(), e);
        }
    }

    public void deleteEmplacement(String id) {
        scope.supprimer("DELETE FROM emplacement WHERE edition_id = ? AND id = ?", id);
    }

    void upsertEmplacementTx(Connection connection, Emplacement emplacement) throws SQLException {
        try (PreparedStatement ps = scope.prepareScoped(connection,
                """
                INSERT INTO emplacement (edition_id, id, nom, latitude, longitude)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (edition_id, id)
                DO UPDATE SET nom = EXCLUDED.nom, latitude = EXCLUDED.latitude, longitude = EXCLUDED.longitude""")) {
            ps.setString(2, emplacement.getId());
            ps.setString(3, emplacement.getNom());
            ps.setObject(4, emplacement.getLatitude());
            ps.setObject(5, emplacement.getLongitude());
            ps.executeUpdate();
        }
    }
}
