package dev.sylvain.planning.service.referentiel;

import dev.sylvain.planning.domain.Emplacement;
import dev.sylvain.planning.service.ConcurrentModificationGuard;
import dev.sylvain.planning.service.JdbcEditionScope;
import dev.sylvain.planning.service.WriteStamp;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;

/** The emplacement rows stands are pinned to — a flat referential, and the only one with coordinates. */
@ApplicationScoped
public class EmplacementRepository {

    private final ConcurrentModificationGuard staleWrites;

    private final DataSource dataSource;

    private final JdbcEditionScope scope;

    @Inject
    public EmplacementRepository(
            ConcurrentModificationGuard staleWrites, DataSource dataSource, JdbcEditionScope scope) {
        this.staleWrites = staleWrites;
        this.dataSource = dataSource;
        this.scope = scope;
    }

    public List<Emplacement> listEmplacements() {
        List<Emplacement> emplacements = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = scope.prepareScoped(
                        connection,
                        "SELECT id, nom, latitude, longitude, modifie_le FROM emplacement WHERE edition_id = ? ORDER BY id");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Emplacement emplacement = new Emplacement(
                        rs.getString("id"), rs.getString("nom"), (Double) rs.getObject("latitude"), (Double)
                                rs.getObject("longitude"));
                emplacement.setModifieLe(
                        rs.getObject("modifie_le", OffsetDateTime.class).toInstant());
                emplacements.add(emplacement);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list emplacements", e);
        }
        return emplacements;
    }

    public boolean emplacementExists(String id) {
        return scope.exists("emplacement", id);
    }

    /**
     * Writes it, refusing a creation whose id is taken and an update based on an
     * out-of-date read (issue #362): both are the write's own precondition.
     *
     * @param failIfPresent true on a creation — an existing row is then a 409,
     *                      not a silent replacement
     */
    public void saveEmplacement(Emplacement emplacement, boolean failIfPresent) {
        try (Connection connection = dataSource.getConnection()) {
            upsertEmplacementTx(connection, emplacement, failIfPresent);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save emplacement " + emplacement.getId(), e);
        }
    }

    /** The same write inside a caller's transaction. */
    void saveEmplacement(Connection connection, Emplacement emplacement, boolean failIfPresent) throws SQLException {
        upsertEmplacementTx(connection, emplacement, failIfPresent);
    }

    public void deleteEmplacement(String id) {
        scope.delete("DELETE FROM emplacement WHERE edition_id = ? AND id = ?", id);
    }

    /** The write's own precondition said no: a taken id on a creation, a stale read otherwise. */
    private void refuse(boolean failIfPresent, String table, String id) {
        if (failIfPresent) {
            staleWrites.refuseDuplicate(table, id);
        }
        staleWrites.refuseStale(table, id);
    }

    void upsertEmplacementTx(Connection connection, Emplacement emplacement) throws SQLException {
        upsertEmplacementTx(connection, emplacement, false);
    }

    void upsertEmplacementTx(Connection connection, Emplacement emplacement, boolean failIfPresent)
            throws SQLException {
        try (PreparedStatement ps = scope.prepareScoped(connection, """
                INSERT INTO emplacement (edition_id, id, nom, latitude, longitude)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (edition_id, id)
                DO UPDATE SET nom = EXCLUDED.nom, latitude = EXCLUDED.latitude, longitude = EXCLUDED.longitude,
                modifie_le = now()
                WHERE CAST(? AS boolean)
                AND (CAST(? AS timestamptz) IS NULL
                     OR date_trunc('milliseconds', emplacement.modifie_le)
                        = date_trunc('milliseconds', CAST(? AS timestamptz)))
                RETURNING modifie_le""")) {
            ps.setString(2, emplacement.getId());
            ps.setString(3, emplacement.getNom());
            ps.setObject(4, emplacement.getLatitude());
            ps.setObject(5, emplacement.getLongitude());
            WriteStamp.bindPrecondition(ps, 6, !failIfPresent, emplacement.getModifieLe());
            Instant ecrit = WriteStamp.writtenOrRefused(ps);
            if (ecrit == null) {
                refuse(failIfPresent, "emplacement", emplacement.getId());
            }
            emplacement.setModifieLe(ecrit);
        }
    }
}
