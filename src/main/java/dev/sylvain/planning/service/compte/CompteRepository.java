package dev.sylvain.planning.service.compte;

import dev.sylvain.planning.service.JdbcEditionScope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The {@code compte} and {@code habilitation} tables. Deliberately not
 * edition-scoped — one account serves every edition, and a right's edition is
 * the scope it grants, not a partition (see
 * {@code IsolationEditionStructurelleTest.TABLES_HORS_EDITION}) — hence plain
 * {@code prepareStatement} rather than {@code scope.prepareScoped}.
 *
 * <p>No statement here deletes a row: accounts are deactivated and rights
 * withdrawn, so the history keeps meaning something.</p>
 */
@ApplicationScoped
public class CompteRepository {

    private static final String SELECT_COMPTE =
            "SELECT id, email, nom, cree_le, derniere_connexion_le, desactive_le FROM compte";

    @Inject
    JdbcEditionScope scope;

    public List<Compte> list() {
        return scope.read("Failed to list the accounts", connection -> {
            Map<String, List<Habilitation>> habilitations = habilitations(connection, null);
            List<Compte> comptes = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(SELECT_COMPTE + " ORDER BY lower(email)");
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    comptes.add(compte(rs, habilitations));
                }
            }
            return comptes;
        });
    }

    public Optional<Compte> findById(String id) {
        return find(SELECT_COMPTE + " WHERE id = ?", id);
    }

    public Optional<Compte> findByEmail(String email) {
        return find(SELECT_COMPTE + " WHERE lower(email) = lower(?)", email);
    }

    private Optional<Compte> find(String sql, String valeur) {
        return scope.read("Failed to read an account", connection -> {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, valeur);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    Compte trouve = compte(rs, Map.of());
                    return Optional.of(new Compte(
                            trouve.id(),
                            trouve.email(),
                            trouve.nom(),
                            trouve.creeLe(),
                            trouve.derniereConnexionLe(),
                            trouve.desactiveLe(),
                            habilitations(connection, trouve.id()).getOrDefault(trouve.id(), List.of())));
                }
            }
        });
    }

    /**
     * Inserts the account unless one already holds this address; answers
     * whether this call created it. {@code ON CONFLICT DO NOTHING} rather than
     * a read then a write: two concurrent creations must not make the second
     * one fail on the unique index.
     */
    public boolean insertIfAbsent(String id, String email, String nom) {
        return scope.writeAndReturn("Failed to create an account", connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO compte (id, email, nom) VALUES (?, ?, ?) ON CONFLICT DO NOTHING")) {
                ps.setString(1, id);
                ps.setString(2, email.trim().toLowerCase(Locale.ROOT));
                ps.setString(3, nom);
                return ps.executeUpdate() == 1;
            }
        });
    }

    /** Deactivates ({@code desactiveLe} set) or reactivates ({@code null}); answers whether the account exists. */
    public boolean setDeactivated(String id, Instant desactiveLe) {
        return scope.writeAndReturn("Failed to change an account's state", connection -> {
            try (PreparedStatement ps =
                    connection.prepareStatement("UPDATE compte SET desactive_le = ? WHERE id = ?")) {
                ps.setTimestamp(1, desactiveLe == null ? null : Timestamp.from(desactiveLe));
                ps.setString(2, id);
                return ps.executeUpdate() == 1;
            }
        });
    }

    public void insertHabilitation(String compteId, Habilitation habilitation) {
        scope.write("Failed to grant a right", connection -> {
            try (PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO habilitation (id, compte_id, role, edition_id, expire_le, cree_par)
                    VALUES (?, ?, ?, ?, ?, ?)""")) {
                ps.setString(1, habilitation.id());
                ps.setString(2, compteId);
                ps.setString(3, habilitation.role().name());
                ps.setString(4, habilitation.editionId());
                ps.setTimestamp(5, habilitation.expireLe() == null ? null : Timestamp.from(habilitation.expireLe()));
                ps.setString(6, habilitation.creePar());
                ps.executeUpdate();
            }
        });
    }

    /** Withdraws a right still in force; answers whether there was one to withdraw. */
    public boolean withdrawHabilitation(String compteId, String habilitationId, Instant retireeLe) {
        return scope.writeAndReturn("Failed to withdraw a right", connection -> {
            try (PreparedStatement ps = connection.prepareStatement("""
                    UPDATE habilitation SET retiree_le = ?
                    WHERE id = ? AND compte_id = ? AND retiree_le IS NULL""")) {
                ps.setTimestamp(1, Timestamp.from(retireeLe));
                ps.setString(2, habilitationId);
                ps.setString(3, compteId);
                return ps.executeUpdate() == 1;
            }
        });
    }

    /** Every right of one account ({@code compteId}), or of all of them ({@code null}), by account. */
    private static Map<String, List<Habilitation>> habilitations(Connection connection, String compteId)
            throws SQLException {
        Map<String, List<Habilitation>> parCompte = new LinkedHashMap<>();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT id, compte_id, role, edition_id, expire_le, cree_par, cree_le, retiree_le
                FROM habilitation WHERE (CAST(? AS VARCHAR) IS NULL OR compte_id = ?) ORDER BY cree_le, id""")) {
            ps.setString(1, compteId);
            ps.setString(2, compteId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    parCompte
                            .computeIfAbsent(rs.getString("compte_id"), ignore -> new ArrayList<>())
                            .add(new Habilitation(
                                    rs.getString("id"),
                                    RoleHabilitation.valueOf(rs.getString("role")),
                                    rs.getString("edition_id"),
                                    instant(rs, "expire_le"),
                                    rs.getString("cree_par"),
                                    instant(rs, "cree_le"),
                                    instant(rs, "retiree_le")));
                }
            }
        }
        return parCompte;
    }

    private static Compte compte(ResultSet rs, Map<String, List<Habilitation>> habilitations) throws SQLException {
        String id = rs.getString("id");
        return new Compte(
                id,
                rs.getString("email"),
                rs.getString("nom"),
                instant(rs, "cree_le"),
                instant(rs, "derniere_connexion_le"),
                instant(rs, "desactive_le"),
                List.copyOf(habilitations.getOrDefault(id, List.of())));
    }

    private static Instant instant(ResultSet rs, String colonne) throws SQLException {
        Timestamp valeur = rs.getTimestamp(colonne);
        return valeur == null ? null : valeur.toInstant();
    }
}
