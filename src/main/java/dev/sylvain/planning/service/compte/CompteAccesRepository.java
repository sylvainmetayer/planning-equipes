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
import java.util.Optional;

/**
 * What a local sign-in keeps: the password hash on {@code compte}, the
 * single-use links ({@code compte_jeton}) and the sign-in challenges
 * ({@code compte_defi}). Global tables, like {@code compte}: no edition.
 *
 * <p>Every value here is already a hash: the callers hash a token, a challenge
 * or a code before it reaches a statement, so nothing a user could use ever
 * lands in a table, a log or a failure message.</p>
 */
@ApplicationScoped
public class CompteAccesRepository {

    @Inject
    JdbcEditionScope scope;

    /** What a password check needs of an account, active or not. */
    public record Credentials(String id, String email, String nom, String passwordHash, boolean active) {}

    public Optional<Credentials> credentials(String email) {
        return scope.read("Failed to read the credentials of an account", connection -> {
            try (PreparedStatement ps = connection.prepareStatement("""
                    SELECT id, email, nom, mot_de_passe_hash, desactive_le IS NULL AS actif
                    FROM compte WHERE lower(email) = lower(?)""")) {
                ps.setString(1, email);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next()
                            ? Optional.of(new Credentials(
                                    rs.getString("id"),
                                    rs.getString("email"),
                                    rs.getString("nom"),
                                    rs.getString("mot_de_passe_hash"),
                                    rs.getBoolean("actif")))
                            : Optional.empty();
                }
            }
        });
    }

    /**
     * One link in force per account: a new one replaces the previous, in one
     * statement — two concurrent emissions cannot both insert and fail on the
     * unique index.
     */
    public void replaceLink(String compteId, String tokenHash, String objet, Instant expireLe) {
        scope.write("Failed to store a password link", connection -> {
            purgeLinks(connection);
            try (PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO compte_jeton (jeton_hash, compte_id, objet, expire_le) VALUES (?, ?, ?, ?)
                    ON CONFLICT (compte_id) DO UPDATE SET jeton_hash = EXCLUDED.jeton_hash,
                    objet = EXCLUDED.objet, expire_le = EXCLUDED.expire_le, cree_le = now()""")) {
                ps.setString(1, tokenHash);
                ps.setString(2, compteId);
                ps.setString(3, objet);
                ps.setTimestamp(4, Timestamp.from(expireLe));
                ps.executeUpdate();
            }
        });
    }

    /** Whether a link still in force exists: asking for another must not cancel it. */
    public boolean hasLiveLink(String compteId) {
        return scope.read("Failed to read a password link", connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT 1 FROM compte_jeton WHERE compte_id = ? AND expire_le > now()")) {
                ps.setString(1, compteId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
    }

    /**
     * Spends a link and sets the password, in <b>one transaction</b>: a failure
     * between the two cannot burn the link without changing the password.
     *
     * @return the account id; empty on an unknown, expired or used link, or an
     *         account deactivated since (the link is then spent for nothing)
     */
    public Optional<String> useLink(String tokenHash, String passwordHash) {
        return scope.writeAndReturn("Failed to use a password link", connection -> {
            String compteId;
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM compte_jeton WHERE jeton_hash = ? AND expire_le > now() RETURNING compte_id")) {
                ps.setString(1, tokenHash);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    compteId = rs.getString(1);
                }
            }
            try (PreparedStatement ps = connection.prepareStatement("""
                    UPDATE compte SET mot_de_passe_hash = ?, mot_de_passe_le = now()
                    WHERE id = ? AND desactive_le IS NULL""")) {
                ps.setString(1, passwordHash);
                ps.setString(2, compteId);
                if (ps.executeUpdate() != 1) {
                    return Optional.empty();
                }
            }
            deleteChallenges(connection, compteId);
            return Optional.of(compteId);
        });
    }

    /**
     * The generation of the account's sessions: when its password was last set
     * (milliseconds, {@code 0} before any). A session carries the value it was
     * opened under; a new password makes every older one worthless.
     */
    public long sessionEpoch(String compteId) {
        return scope.read("Failed to read a session epoch", connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT COALESCE((date_part('epoch', mot_de_passe_le) * 1000)::bigint, 0) FROM compte WHERE id = ?")) {
                ps.setString(1, compteId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0L;
                }
            }
        });
    }

    /** One challenge per account; {@code codeHash} is {@code null} when there is no second step. */
    public void replaceChallenge(
            String compteId, String challengeHash, String codeHash, int attempts, Instant expireLe) {
        scope.write("Failed to store a sign-in challenge", connection -> {
            purgeChallenges(connection);
            try (PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO compte_defi (defi_hash, compte_id, code_hash, tentatives_restantes, expire_le)
                    VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT (compte_id) DO UPDATE SET defi_hash = EXCLUDED.defi_hash,
                    code_hash = EXCLUDED.code_hash, tentatives_restantes = EXCLUDED.tentatives_restantes,
                    expire_le = EXCLUDED.expire_le""")) {
                ps.setString(1, challengeHash);
                ps.setString(2, compteId);
                ps.setString(3, codeHash);
                ps.setInt(4, attempts);
                ps.setTimestamp(5, Timestamp.from(expireLe));
                ps.executeUpdate();
            }
        });
    }

    /**
     * Redeems a challenge, once. {@code codeHash} is the hash the submitted code
     * has for this challenge (the salt is the challenge itself). The
     * attempt is counted and the row locked in one transaction: two parallel
     * guesses cannot both read the same remaining count.
     *
     * @return the account id on success; empty on an unknown, expired, used,
     *         exhausted challenge, or a wrong or missing code
     */
    public Optional<String> redeemChallenge(String challengeHash, String codeHash) {
        return scope.writeAndReturn("Failed to redeem a sign-in challenge", connection -> {
            String compteId;
            String attendu;
            int restantes;
            try (PreparedStatement ps = connection.prepareStatement("""
                    SELECT compte_id, code_hash, tentatives_restantes FROM compte_defi
                    WHERE defi_hash = ? AND expire_le > now() FOR UPDATE""")) {
                ps.setString(1, challengeHash);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    compteId = rs.getString("compte_id");
                    attendu = rs.getString("code_hash");
                    restantes = rs.getInt("tentatives_restantes");
                }
            }
            if (attendu != null && codeHash == null) {
                return Optional.empty(); // the second step is not skipped, and asking costs no attempt
            }
            boolean bon = restantes > 0
                    && (attendu == null
                            || java.security.MessageDigest.isEqual(
                                    attendu.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                                    codeHash.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            if (bon || restantes <= 1) {
                try (PreparedStatement ps =
                        connection.prepareStatement("DELETE FROM compte_defi WHERE defi_hash = ?")) {
                    ps.setString(1, challengeHash);
                    ps.executeUpdate();
                }
            } else {
                try (PreparedStatement ps = connection.prepareStatement(
                        "UPDATE compte_defi SET tentatives_restantes = tentatives_restantes - 1 WHERE defi_hash = ?")) {
                    ps.setString(1, challengeHash);
                    ps.executeUpdate();
                }
            }
            return bon ? Optional.of(compteId) : Optional.empty();
        });
    }

    private static void deleteChallenges(Connection connection, String compteId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM compte_defi WHERE compte_id = ?")) {
            ps.setString(1, compteId);
            ps.executeUpdate();
        }
    }

    /** Expired rows go when the next one is written: no job for tables this small. */
    private static void purgeLinks(Connection connection) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM compte_jeton WHERE expire_le < now()")) {
            ps.executeUpdate();
        }
    }

    private static void purgeChallenges(Connection connection) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM compte_defi WHERE expire_le < now()")) {
            ps.executeUpdate();
        }
    }
}
