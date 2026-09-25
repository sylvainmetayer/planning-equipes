package dev.sylvain.planning.service.referentiel;

import dev.sylvain.planning.service.JdbcEditionScope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code ponderation_contrainte_historique}: append-only, scoped to the current
 * edition like every business table, and gone with it ({@code ON DELETE
 * CASCADE}).
 */
@ApplicationScoped
public class WeightHistoryRepository {

    private static final String INSERT = """
            INSERT INTO ponderation_contrainte_historique
                (edition_id, nom, poids_avant, poids_apres, retour_defaut, actif_avant, actif_apres, origine,
                 edition_source)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""";

    private static final String SELECT = """
            SELECT id, nom, poids_avant, poids_apres, retour_defaut, actif_avant, actif_apres, origine,
                   edition_source, cree_le
            FROM ponderation_contrainte_historique
            WHERE edition_id = ? AND (CAST(? AS VARCHAR) IS NULL OR nom = ?)
            ORDER BY cree_le, id""";

    private final JdbcEditionScope scope;

    @Inject
    public WeightHistoryRepository(JdbcEditionScope scope) {
        this.scope = scope;
    }

    /** Writes one line on the caller's connection, so it commits or rolls back with the change it describes. */
    void insert(Connection connection, WeightChange change) throws SQLException {
        try (PreparedStatement ps = scope.prepareScoped(connection, INSERT)) {
            ps.setString(2, change.name());
            ps.setObject(3, change.weightBefore(), Types.INTEGER);
            ps.setObject(4, change.weightAfter(), Types.INTEGER);
            ps.setBoolean(5, change.backToDefault());
            ps.setObject(6, change.activeBefore(), Types.BOOLEAN);
            ps.setObject(7, change.activeAfter(), Types.BOOLEAN);
            ps.setString(8, change.origin().name());
            ps.setString(9, change.sourceEdition());
            ps.executeUpdate();
        }
    }

    /** The edition's lines, oldest first; {@code name} {@code null} for every rule. */
    public List<WeightChange> list(String name) {
        return scope.read("Failed to read the weight history", connection -> {
            try (PreparedStatement ps = scope.prepareScoped(connection, SELECT)) {
                ps.setString(2, name);
                ps.setString(3, name);
                List<WeightChange> lines = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        WeightChangeOrigin origin = WeightChangeOrigin.parse(rs.getString("origine"));
                        Timestamp creeLe = rs.getTimestamp("cree_le");
                        lines.add(new WeightChange(
                                rs.getLong("id"),
                                rs.getString("nom"),
                                rs.getObject("poids_avant", Integer.class),
                                rs.getObject("poids_apres", Integer.class),
                                rs.getBoolean("retour_defaut"),
                                rs.getObject("actif_avant", Boolean.class),
                                rs.getObject("actif_apres", Boolean.class),
                                origin,
                                origin.acteur(),
                                rs.getString("edition_source"),
                                creeLe == null ? null : creeLe.toInstant()));
                    }
                }
                return lines;
            }
        });
    }
}
