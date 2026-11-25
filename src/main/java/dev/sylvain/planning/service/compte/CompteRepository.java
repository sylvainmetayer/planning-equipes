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
            "SELECT id, email, nom, cree_le, derniere_connexion_le, desactive_le, desactive_par FROM compte";

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

    public Optional<Compte> findBySubject(String sujet) {
        return find(SELECT_COMPTE + " WHERE oidc_sujet = ?", sujet);
    }

    /**
     * Ties the provider's subject to the account if it has none yet; answers
     * whether it is now tied to <b>this</b> subject. The condition is in the
     * statement, not in a prior read: two first sign-ins must not both win.
     */
    public boolean bindSubject(String id, String sujet) {
        return scope.writeAndReturn("Failed to bind an account to its subject", connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE compte SET oidc_sujet = ? WHERE id = ? AND (oidc_sujet IS NULL OR oidc_sujet = ?)")) {
                ps.setString(1, sujet);
                ps.setString(2, id);
                ps.setString(3, sujet);
                return ps.executeUpdate() == 1;
            }
        });
    }

    /** Stamps the last sign-in, at most once an hour: this runs on every request. */
    public void touchSignIn(String id) {
        scope.write("Failed to stamp a sign-in", connection -> {
            try (PreparedStatement ps = connection.prepareStatement("""
                    UPDATE compte SET derniere_connexion_le = now()
                    WHERE id = ? AND (derniere_connexion_le IS NULL
                                      OR derniere_connexion_le < now() - interval '1 hour')""")) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
        });
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
                            trouve.desactivePar(),
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

    /** What a guarded write did: it needs no exception, the service says it in its own words. */
    public enum Outcome {
        DONE,
        NOT_FOUND,
        LAST_ADMIN
    }

    /** Serialises the writes that must leave an administrator behind; released with the transaction. */
    private static final String ADMIN_LOCK = "SELECT pg_advisory_xact_lock(130)";

    /** An active account other than those excluded holding an administrator right in force. */
    private static final String OTHER_ADMIN = """
            SELECT 1 FROM habilitation h JOIN compte c ON c.id = h.compte_id
            WHERE h.role = 'ADMIN' AND h.retiree_le IS NULL AND (h.expire_le IS NULL OR h.expire_le > now())
            AND c.desactive_le IS NULL AND h.compte_id <> ? AND h.id <> ?""";

    private static void lock(Connection connection) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(ADMIN_LOCK)) {
            ps.execute();
        }
    }

    private static boolean otherAdmin(Connection connection, String compteId, String habilitationId)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(OTHER_ADMIN)) {
            ps.setString(1, compteId);
            ps.setString(2, habilitationId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * Deactivates ({@code desactiveLe} set, by {@code par}) or reactivates
     * ({@code null}) an account. Deactivating the last active administrator is
     * refused, atomically: the check and the write share one transaction under
     * a lock, so two concurrent calls cannot each leave the other behind.
     */
    public Outcome setDeactivated(String id, Instant desactiveLe, String par) {
        return scope.writeAndReturn("Failed to change an account's state", connection -> {
            lock(connection);
            if (desactiveLe != null && isActiveAdmin(connection, id) && !otherAdmin(connection, id, "")) {
                return Outcome.LAST_ADMIN;
            }
            try (PreparedStatement ps =
                    connection.prepareStatement("UPDATE compte SET desactive_le = ?, desactive_par = ? WHERE id = ?")) {
                ps.setTimestamp(1, desactiveLe == null ? null : Timestamp.from(desactiveLe));
                ps.setString(2, desactiveLe == null ? null : par);
                ps.setString(3, id);
                return ps.executeUpdate() == 1 ? Outcome.DONE : Outcome.NOT_FOUND;
            }
        });
    }

    private static boolean isActiveAdmin(Connection connection, String compteId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT 1 FROM habilitation h JOIN compte c ON c.id = h.compte_id
                WHERE h.compte_id = ? AND h.role = 'ADMIN' AND h.retiree_le IS NULL
                AND (h.expire_le IS NULL OR h.expire_le > now()) AND c.desactive_le IS NULL""")) {
            ps.setString(1, compteId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * Inserts a right; {@code false} when the same one is already in force.
     * The same right left expired but not withdrawn does not count: it is
     * withdrawn first, in the same transaction, by whoever grants anew.
     */
    public boolean insertHabilitation(String compteId, Habilitation habilitation) {
        return scope.writeAndReturn("Failed to grant a right", connection -> {
            try (PreparedStatement ps = connection.prepareStatement("""
                    UPDATE habilitation SET retiree_le = now(), retiree_par = ?
                    WHERE compte_id = ? AND role = ? AND COALESCE(edition_id, '') = COALESCE(?, '')
                    AND retiree_le IS NULL AND expire_le IS NOT NULL AND expire_le <= now()""")) {
                ps.setString(1, habilitation.creePar());
                ps.setString(2, compteId);
                ps.setString(3, habilitation.role().name());
                ps.setString(4, habilitation.editionId());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO habilitation (id, compte_id, role, edition_id, expire_le, cree_par)
                    VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING""")) {
                ps.setString(1, habilitation.id());
                ps.setString(2, compteId);
                ps.setString(3, habilitation.role().name());
                ps.setString(4, habilitation.editionId());
                ps.setTimestamp(5, habilitation.expireLe() == null ? null : Timestamp.from(habilitation.expireLe()));
                ps.setString(6, habilitation.creePar());
                if (ps.executeUpdate() != 1) {
                    return false;
                }
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO habilitation_stand (habilitation_id, stand_id) VALUES (?, ?)")) {
                for (String standId : habilitation.standIds()) {
                    ps.setString(1, habilitation.id());
                    ps.setString(2, standId);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return true;
        });
    }

    /**
     * Withdraws a right still in force. Withdrawing the last administrator
     * right in force is refused, under the same lock as a deactivation.
     */
    public Outcome withdrawHabilitation(String compteId, String habilitationId, Instant retireeLe, String par) {
        return scope.writeAndReturn("Failed to withdraw a right", connection -> {
            lock(connection);
            boolean admin;
            try (PreparedStatement ps = connection.prepareStatement("""
                    SELECT h.role, (c.desactive_le IS NULL AND (h.expire_le IS NULL OR h.expire_le > now())) AS vivant
                    FROM habilitation h JOIN compte c ON c.id = h.compte_id
                    WHERE h.id = ? AND h.compte_id = ? AND h.retiree_le IS NULL""")) {
                ps.setString(1, habilitationId);
                ps.setString(2, compteId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Outcome.NOT_FOUND;
                    }
                    admin = "ADMIN".equals(rs.getString("role")) && rs.getBoolean("vivant");
                }
            }
            if (admin && !otherAdmin(connection, "", habilitationId)) {
                return Outcome.LAST_ADMIN;
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE habilitation SET retiree_le = ?, retiree_par = ? WHERE id = ?")) {
                ps.setTimestamp(1, Timestamp.from(retireeLe));
                ps.setString(2, par);
                ps.setString(3, habilitationId);
                ps.executeUpdate();
                return Outcome.DONE;
            }
        });
    }

    /** Withdraws every right still in force on a deleted edition: it can open nothing any more. */
    public void withdrawForEdition(String editionId, Instant retireeLe, String par) {
        scope.write("Failed to withdraw the rights of an edition", connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE habilitation SET retiree_le = ?, retiree_par = ? WHERE edition_id = ? AND retiree_le IS NULL")) {
                ps.setTimestamp(1, Timestamp.from(retireeLe));
                ps.setString(2, par);
                ps.setString(3, editionId);
                ps.executeUpdate();
            }
        });
    }

    /** Every right of one account ({@code compteId}), or of all of them ({@code null}), by account. */
    private static Map<String, List<Habilitation>> habilitations(Connection connection, String compteId)
            throws SQLException {
        Map<String, List<Habilitation>> parCompte = new LinkedHashMap<>();
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
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT id, compte_id, role, edition_id, expire_le, cree_par, cree_le, retiree_le, retiree_par
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
                                    instant(rs, "retiree_le"),
                                    rs.getString("retiree_par"),
                                    List.copyOf(stands.getOrDefault(rs.getString("id"), List.of()))));
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
                rs.getString("desactive_par"),
                List.copyOf(habilitations.getOrDefault(id, List.of())));
    }

    private static Instant instant(ResultSet rs, String colonne) throws SQLException {
        Timestamp valeur = rs.getTimestamp(colonne);
        return valeur == null ? null : valeur.toInstant();
    }
}
