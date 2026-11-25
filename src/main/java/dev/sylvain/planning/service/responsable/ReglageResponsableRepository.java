package dev.sylvain.planning.service.responsable;

import dev.sylvain.planning.service.JdbcEditionScope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** The two settings tables of the responsable de stand, scoped to the edition of the request. */
@ApplicationScoped
public class ReglageResponsableRepository {

    @Inject
    JdbcEditionScope scope;

    public ReglagesResponsables get() {
        return scope.read("Failed to load the responsable settings", connection -> {
            ReglageNoms parDefaut = ReglageNoms.HORAIRES_SEULS;
            try (PreparedStatement ps = scope.prepareScoped(
                            connection,
                            "SELECT noms_visibles, ouverture_le FROM reglage_noms_responsable WHERE edition_id = ?");
                    ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    parDefaut = reglage(rs);
                }
            }
            Map<String, ReglageNoms> parStand = new LinkedHashMap<>();
            try (PreparedStatement ps = scope.prepareScoped(
                            connection,
                            "SELECT stand_id, noms_visibles, ouverture_le FROM reglage_noms_stand "
                                    + "WHERE edition_id = ? ORDER BY stand_id");
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    parStand.put(rs.getString("stand_id"), reglage(rs));
                }
            }
            return new ReglagesResponsables(parDefaut, parStand);
        });
    }

    /**
     * Writes the edition's default and the stand exceptions the body names, in one transaction:
     * a {@code null} value lifts that stand's exception, a stand the body does not name is left
     * as it is — a client that did not know a stand cannot erase what was set on it.
     */
    public void replace(ReglagesResponsables reglages) {
        scope.write("Failed to save the responsable settings", connection -> {
            try (PreparedStatement ps = scope.prepareScoped(connection, """
                    INSERT INTO reglage_noms_responsable (edition_id, noms_visibles, ouverture_le)
                    VALUES (?, ?, ?)
                    ON CONFLICT (edition_id)
                    DO UPDATE SET noms_visibles = EXCLUDED.noms_visibles, ouverture_le = EXCLUDED.ouverture_le""")) {
                bind(ps, 2, reglages.parDefaut());
                ps.executeUpdate();
            }
            try (PreparedStatement delete = scope.prepareScoped(
                            connection, "DELETE FROM reglage_noms_stand WHERE edition_id = ? AND stand_id = ?");
                    PreparedStatement upsert = scope.prepareScoped(connection, """
                            INSERT INTO reglage_noms_stand (edition_id, stand_id, noms_visibles, ouverture_le)
                            VALUES (?, ?, ?, ?)
                            ON CONFLICT (edition_id, stand_id)
                            DO UPDATE SET noms_visibles = EXCLUDED.noms_visibles,
                            ouverture_le = EXCLUDED.ouverture_le""")) {
                for (var exception : reglages.parStand().entrySet()) {
                    if (exception.getValue() == null) {
                        delete.setString(2, exception.getKey());
                        delete.executeUpdate();
                    } else {
                        upsert.setString(2, exception.getKey());
                        bind(upsert, 3, exception.getValue());
                        upsert.executeUpdate();
                    }
                }
            }
        });
    }

    private static ReglageNoms reglage(ResultSet rs) throws java.sql.SQLException {
        Timestamp ouverture = rs.getTimestamp("ouverture_le");
        return new ReglageNoms(rs.getBoolean("noms_visibles"), ouverture == null ? null : ouverture.toInstant());
    }

    private static void bind(PreparedStatement ps, int index, ReglageNoms reglage) throws java.sql.SQLException {
        ps.setBoolean(index, reglage.nomsVisibles());
        // The date means nothing while names are off: not kept, so a later « on » never inherits a stale one.
        Instant ouverture = reglage.nomsVisibles() ? reglage.ouvertureLe() : null;
        ps.setTimestamp(index + 1, ouverture == null ? null : Timestamp.from(ouverture));
    }
}
