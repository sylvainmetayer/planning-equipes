package dev.sylvain.planning.service.espace;

import dev.sylvain.planning.domain.DemandeCoequipier;
import dev.sylvain.planning.domain.NatureCoequipier;
import dev.sylvain.planning.domain.StatutDemandeCoequipier;
import dev.sylvain.planning.service.JdbcEditionScope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * SQL of the teammates an animateur names from their espace — the table
 * {@code declaration_coequipier}, every statement scoped through
 * {@link JdbcEditionScope}.
 *
 * <p>The teammates are stored one id per line in a {@code TEXT} column, the
 * shape the declaration's own days and wishes use: a demand is the frozen
 * snapshot of what somebody typed.</p>
 */
@ApplicationScoped
public class TeammateDeclarationRepository {

    private static final String COLONNES =
            "id, animateur_id, nature, coequipiers, statut, contrainte_id, cree_le, decide_le, motif";

    private final JdbcEditionScope scope;

    @Inject
    public TeammateDeclarationRepository(JdbcEditionScope scope) {
        this.scope = scope;
    }

    /** Every demand of the edition, most recent first, whatever its statut. */
    public List<DemandeCoequipier> list() {
        return select("", null);
    }

    /** One animateur's demands, most recent first. */
    public List<DemandeCoequipier> listForAnimateur(String animateurId) {
        return select(" AND animateur_id = ?", animateurId);
    }

    public Optional<DemandeCoequipier> byId(String id) {
        return select(" AND id = ?", id).stream().findFirst();
    }

    /**
     * The upsert behind {@link #replacePending}: the conflict target is the
     * partial unique index, so two submissions racing each other end with one
     * pending row rather than a 500 — the reasoning of the declaration's own
     * upsert.
     */
    private static final String UPSERT_SQL = "INSERT INTO declaration_coequipier (edition_id, " + COLONNES + ") "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
            + "ON CONFLICT (edition_id, animateur_id, nature) WHERE statut = 'EN_ATTENTE' "
            + "DO UPDATE SET id = EXCLUDED.id, coequipiers = EXCLUDED.coequipiers, cree_le = EXCLUDED.cree_le";

    /** Replaces the animateur's pending demand of that nature with {@code demande}. */
    public void replacePending(DemandeCoequipier demande) {
        scope.write("Failed to store the teammates of animateur " + demande.animateurId(), connection -> {
            try (PreparedStatement upsert = scope.prepareScoped(connection, UPSERT_SQL)) {
                upsert.setString(2, demande.id());
                upsert.setString(3, demande.animateurId());
                upsert.setString(4, demande.nature().name());
                upsert.setString(5, String.join("\n", demande.coequipiers()));
                upsert.setString(6, demande.statut().name());
                upsert.setString(7, demande.contrainteId());
                upsert.setTimestamp(8, Timestamp.from(demande.creeLe()));
                upsert.setTimestamp(9, demande.decideLe() == null ? null : Timestamp.from(demande.decideLe()));
                upsert.setString(10, demande.motif());
                upsert.executeUpdate();
            }
        });
    }

    /** Withdraws the animateur's pending demand of that nature, when a new request names nobody. */
    public void deletePending(String animateurId, NatureCoequipier nature) {
        scope.write("Failed to withdraw the teammates of animateur " + animateurId, connection -> {
            try (PreparedStatement ps = scope.prepareScoped(
                    connection,
                    "DELETE FROM declaration_coequipier WHERE edition_id = ? AND animateur_id = ? AND nature = ? "
                            + "AND statut = 'EN_ATTENTE'")) {
                ps.setString(2, animateurId);
                ps.setString(3, nature.name());
                ps.executeUpdate();
            }
        });
    }

    /**
     * Records the admin's verdict on one pending demand.
     *
     * @param motif the reason given when setting it aside, {@code null} otherwise
     * @return how many rows moved: {@code 0} means somebody decided it first
     */
    public int decide(
            String id, StatutDemandeCoequipier statut, String contrainteId, Timestamp decideLe, String motif) {
        return scope.writeAndReturn("Failed to record the decision on teammates " + id, connection -> {
            // Not prepareScoped: the SET clause claims the first placeholders,
            // so the edition_id predicate is bound explicitly.
            try (PreparedStatement ps = connection.prepareStatement("""
                    UPDATE declaration_coequipier
                    SET statut = ?, contrainte_id = ?, decide_le = ?, motif = ?
                    WHERE edition_id = ? AND id = ? AND statut = 'EN_ATTENTE'""")) {
                ps.setString(1, statut.name());
                ps.setString(2, contrainteId);
                ps.setTimestamp(3, decideLe);
                ps.setString(4, motif);
                ps.setString(5, scope.editionId());
                ps.setString(6, id);
                return ps.executeUpdate();
            }
        });
    }

    /**
     * Cancels a validated grouped arrival in one transaction: every validated
     * demand of the edition pointing at {@code contrainteId} becomes
     * {@code ANNULEE} with the reason, and the {@code ARRIVEE_GROUPEE}
     * exception itself is deleted — its members and scope go with it by
     * cascade. The exception row is deleted here rather than through the ad
     * hoc service, whose delete refuses a request-backed grouped arrival: this
     * is the one gesture allowed to remove it, and the demands and the
     * exception must move together or not at all.
     *
     * @return how many demands moved: {@code 0} means nothing was validated
     *         against that exception any more, or it was already gone, and
     *         nothing was written
     */
    public int cancelGroup(String contrainteId, Timestamp decideLe, String motif) {
        return scope.writeAndReturn("Failed to cancel the grouped arrival " + contrainteId, connection -> {
            int annulees;
            // Not prepareScoped: the SET clause claims the first placeholders,
            // so the edition_id predicate is bound explicitly.
            try (PreparedStatement ps = connection.prepareStatement("""
                    UPDATE declaration_coequipier
                    SET statut = 'ANNULEE', decide_le = ?, motif = ?
                    WHERE edition_id = ? AND contrainte_id = ? AND statut = 'VALIDEE'""")) {
                ps.setTimestamp(1, decideLe);
                ps.setString(2, motif);
                ps.setString(3, scope.editionId());
                ps.setString(4, contrainteId);
                annulees = ps.executeUpdate();
            }
            if (annulees == 0) {
                return 0;
            }
            try (PreparedStatement ps =
                    scope.prepareScoped(connection, "DELETE FROM contrainte_ad_hoc WHERE edition_id = ? AND id = ?")) {
                ps.setString(2, contrainteId);
                if (ps.executeUpdate() == 0) {
                    // The exception went first: the demands stay as they were.
                    connection.rollback();
                    return 0;
                }
            }
            return annulees;
        });
    }

    private List<DemandeCoequipier> select(String predicatSupplementaire, String parametre) {
        // Only the column list and the extra predicate are concatenated, and both
        // are literals from this class's own call sites.
        String sql = "SELECT " + COLONNES + " FROM declaration_coequipier WHERE edition_id = ?" + predicatSupplementaire
                + " ORDER BY cree_le DESC, id";
        return scope.read("Failed to list the declared teammates", connection -> {
            List<DemandeCoequipier> demandes = new ArrayList<>();
            try (PreparedStatement ps = scope.prepareScoped(connection, sql)) {
                if (parametre != null) {
                    ps.setString(2, parametre);
                }
                // nosemgrep: java.lang.security.audit.formatted-sql-string.formatted-sql-string
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        demandes.add(read(rs));
                    }
                }
            }
            return demandes;
        });
    }

    private static DemandeCoequipier read(ResultSet rs) throws SQLException {
        String stockes = rs.getString("coequipiers");
        Timestamp creeLe = rs.getTimestamp("cree_le");
        Timestamp decideLe = rs.getTimestamp("decide_le");
        return new DemandeCoequipier(
                rs.getString("id"),
                rs.getString("animateur_id"),
                NatureCoequipier.valueOf(rs.getString("nature")),
                stockes == null || stockes.isBlank() ? List.of() : List.of(stockes.split("\n")),
                StatutDemandeCoequipier.valueOf(rs.getString("statut")),
                rs.getString("contrainte_id"),
                creeLe == null ? null : creeLe.toInstant(),
                decideLe == null ? null : decideLe.toInstant(),
                rs.getString("motif"));
    }
}
