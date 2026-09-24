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
 * The {@code compte}, {@code habilitation} and {@code habilitation_stand}
 * tables. Deliberately not edition-scoped — one account serves every edition,
 * and an habilitation's edition is the scope it grants, not a partition (see
 * {@code IsolationEditionStructurelleTest.TABLES_HORS_EDITION}) — hence plain
 * {@code prepareStatement} rather than {@code scope.prepareScoped}.
 *
 * <p>No statement here deletes a row: accounts are deactivated and rights
 * withdrawn, so the history keeps meaning something (#77, #296).</p>
 */
@ApplicationScoped
public class CompteRepository {

    private static final String COLONNES_COMPTE = "id, email, nom, sujet, cree_le, derniere_connexion_le, desactive_le";

    @Inject
    JdbcEditionScope scope;

    public List<Compte> list() {
        return scope.read("Failed to list the accounts", connection -> {
            List<Compte> comptes = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(
                            "SELECT " + COLONNES_COMPTE + " FROM compte ORDER BY lower(email)");
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    comptes.add(compte(rs, List.of()));
                }
            }
            Map<String, List<Habilitation>> habilitations = habilitations(connection, null);
            return comptes.stream()
                    .map(c -> withHabilitations(c, habilitations.getOrDefault(c.id(), List.of())))
                    .toList();
        });
    }

    public Optional<Compte> findById(String id) {
        return find("SELECT " + COLONNES_COMPTE + " FROM compte WHERE id = ?", id);
    }

    public Optional<Compte> findBySubject(String sujet) {
        return find("SELECT " + COLONNES_COMPTE + " FROM compte WHERE sujet = ?", sujet);
    }

    public Optional<Compte> findByEmail(String email) {
        return find("SELECT " + COLONNES_COMPTE + " FROM compte WHERE lower(email) = lower(?)", email);
    }

    private Optional<Compte> find(String sql, String valeur) {
        return scope.read("Failed to read an account", connection -> {
            Compte trouve;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, valeur);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    trouve = compte(rs, List.of());
                }
            }
            return Optional.of(withHabilitations(
                    trouve, habilitations(connection, trouve.id()).getOrDefault(trouve.id(), List.of())));
        });
    }

    /**
     * Inserts the account unless one already holds this address or this
     * subject; answers whether this call created it. {@code ON CONFLICT DO
     * NOTHING} rather than a read then a write: two first sign-ins of the same
     * person — the burst of parallel calls a page opens with — must not make
     * the second one fail on the unique index.
     */
    public boolean insertIfAbsent(String id, String email, String nom, String sujet, Instant connexion) {
        return scope.writeAndReturn("Failed to create an account", connection -> {
            try (PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO compte (id, email, nom, sujet, derniere_connexion_le) VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT DO NOTHING""")) {
                ps.setString(1, id);
                ps.setString(2, email.trim().toLowerCase(Locale.ROOT));
                ps.setString(3, nom);
                ps.setString(4, sujet);
                ps.setTimestamp(5, connexion == null ? null : Timestamp.from(connexion));
                return ps.executeUpdate() == 1;
            }
        });
    }

    /**
     * Moves an account to the address the realm now gives it, unless another
     * account already holds that address; answers whether it moved.
     */
    public boolean updateEmail(String id, String email) {
        return scope.writeAndReturn("Failed to change an account's address", connection -> {
            try (PreparedStatement ps = connection.prepareStatement("""
                    UPDATE compte SET email = ? WHERE id = ?
                    AND NOT EXISTS (SELECT 1 FROM compte autre WHERE lower(autre.email) = lower(?) AND autre.id <> ?)""")) {
                String nouvelle = email.trim().toLowerCase(Locale.ROOT);
                ps.setString(1, nouvelle);
                ps.setString(2, id);
                ps.setString(3, nouvelle);
                ps.setString(4, id);
                return ps.executeUpdate() == 1;
            }
        });
    }

    /** Records a sign-in: the subject the first time, the name as the realm now spells it, the date. */
    public void recordSignIn(String id, String nom, String sujet, Instant connexion) {
        scope.write("Failed to record a sign-in", connection -> {
            try (PreparedStatement ps = connection.prepareStatement("""
                    UPDATE compte SET derniere_connexion_le = ?, nom = COALESCE(?, nom),
                    sujet = COALESCE(sujet, ?) WHERE id = ?
                    AND NOT EXISTS (SELECT 1 FROM compte autre WHERE autre.sujet = ? AND autre.id <> ?)""")) {
                ps.setTimestamp(1, Timestamp.from(connexion));
                ps.setString(2, nom);
                ps.setString(3, sujet);
                ps.setString(4, id);
                ps.setString(5, sujet);
                ps.setString(6, id);
                ps.executeUpdate();
            }
        });
    }

    /** Sets or clears {@code desactive_le}; answers whether the account exists. */
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
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO habilitation_stand (habilitation_id, edition_id, stand_id) VALUES (?, ?, ?)")) {
                for (String standId : habilitation.standIds()) {
                    ps.setString(1, habilitation.id());
                    ps.setString(2, habilitation.editionId());
                    ps.setString(3, standId);
                    ps.addBatch();
                }
                ps.executeBatch();
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
        Map<String, List<String>> stands = new LinkedHashMap<>();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT hs.habilitation_id, hs.stand_id FROM habilitation_stand hs
                JOIN habilitation h ON h.id = hs.habilitation_id
                WHERE (CAST(? AS VARCHAR) IS NULL OR h.compte_id = ?) ORDER BY hs.stand_id""")) {
            ps.setString(1, compteId);
            ps.setString(2, compteId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    stands.computeIfAbsent(rs.getString(1), ignore -> new ArrayList<>())
                            .add(rs.getString(2));
                }
            }
        }
        Map<String, List<Habilitation>> parCompte = new LinkedHashMap<>();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT id, compte_id, role, edition_id, expire_le, cree_par, cree_le, retiree_le
                FROM habilitation WHERE (CAST(? AS VARCHAR) IS NULL OR compte_id = ?) ORDER BY cree_le, id""")) {
            ps.setString(1, compteId);
            ps.setString(2, compteId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String id = rs.getString("id");
                    parCompte
                            .computeIfAbsent(rs.getString("compte_id"), ignore -> new ArrayList<>())
                            .add(new Habilitation(
                                    id,
                                    RoleHabilitation.valueOf(rs.getString("role")),
                                    rs.getString("edition_id"),
                                    instant(rs, "expire_le"),
                                    List.copyOf(stands.getOrDefault(id, List.of())),
                                    rs.getString("cree_par"),
                                    instant(rs, "cree_le"),
                                    instant(rs, "retiree_le")));
                }
            }
        }
        return parCompte;
    }

    private static Compte compte(ResultSet rs, List<Habilitation> habilitations) throws SQLException {
        return new Compte(
                rs.getString("id"),
                rs.getString("email"),
                rs.getString("nom"),
                rs.getString("sujet"),
                instant(rs, "cree_le"),
                instant(rs, "derniere_connexion_le"),
                instant(rs, "desactive_le"),
                habilitations);
    }

    private static Compte withHabilitations(Compte c, List<Habilitation> habilitations) {
        return new Compte(
                c.id(),
                c.email(),
                c.nom(),
                c.sujet(),
                c.creeLe(),
                c.derniereConnexionLe(),
                c.desactiveLe(),
                List.copyOf(habilitations));
    }

    private static Instant instant(ResultSet rs, String colonne) throws SQLException {
        Timestamp valeur = rs.getTimestamp(colonne);
        return valeur == null ? null : valeur.toInstant();
    }
}
