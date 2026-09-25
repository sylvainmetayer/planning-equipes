package dev.sylvain.planning.service.referentiel;

import dev.sylvain.planning.service.JdbcEditionScope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/** The frozen families of the current edition — one row per family, none when it is open. */
@ApplicationScoped
public class GelReferentielRepository {

    private static final String SELECT_GEL_SQL = "SELECT famille, fige_le FROM gel_referentiel WHERE edition_id = ?";

    private static final String INSERT_GEL_SQL = """
            INSERT INTO gel_referentiel (edition_id, famille, fige_le)
            VALUES (?, ?, now())
            ON CONFLICT (edition_id, famille) DO NOTHING""";

    private static final String DELETE_GEL_SQL = "DELETE FROM gel_referentiel WHERE edition_id = ? AND famille = ?";

    private final JdbcEditionScope scope;

    @Inject
    public GelReferentielRepository(JdbcEditionScope scope) {
        this.scope = scope;
    }

    /** Every frozen family of the edition, in no particular order. */
    public List<GelReferentiel> list() {
        return scope.read("Failed to read the frozen families", connection -> {
            List<GelReferentiel> gels = new ArrayList<>();
            try (PreparedStatement ps = scope.prepareScoped(connection, SELECT_GEL_SQL);
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Timestamp figeLe = rs.getTimestamp("fige_le");
                    gels.add(new GelReferentiel(
                            ReferentialFamily.valueOf(rs.getString("famille")),
                            figeLe == null ? null : figeLe.toInstant()));
                }
            }
            return gels;
        });
    }

    /**
     * Freezes the family. A family already frozen keeps the date it was frozen
     * on: freezing twice is one freeze, and the padlock keeps saying since when.
     */
    public void freeze(ReferentialFamily famille) {
        scope.write("Failed to freeze " + famille, connection -> {
            try (PreparedStatement ps = scope.prepareScoped(connection, INSERT_GEL_SQL)) {
                ps.setString(2, famille.name());
                ps.executeUpdate();
            }
        });
    }

    /** Lifts the freeze; {@code false} when the family was not frozen. */
    public boolean lift(ReferentialFamily famille) {
        return scope.writeAndReturn("Failed to lift the freeze of " + famille, connection -> {
            try (PreparedStatement ps = scope.prepareScoped(connection, DELETE_GEL_SQL)) {
                ps.setString(2, famille.name());
                return ps.executeUpdate() > 0;
            }
        });
    }
}
