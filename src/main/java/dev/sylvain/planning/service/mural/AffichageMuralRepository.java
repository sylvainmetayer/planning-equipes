package dev.sylvain.planning.service.mural;

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
import java.util.Map;

/**
 * The wall display links of an edition ({@code lien_affichage_mural} and the
 * emplacements a restricted one shows, V107).
 *
 * <p>Every statement but one is edition-scoped. The exception is
 * {@link #resolve}: the token arrives on a public URL with no edition header to
 * believe, so — like the espace and the calendar tokens — it names its edition
 * on its own, and the caller carries on inside {@code EditionContext.executeIn}.
 * Only the SHA-256 of the token is ever stored or looked up.</p>
 */
@ApplicationScoped
public class AffichageMuralRepository {

    private final JdbcEditionScope scope;

    @Inject
    public AffichageMuralRepository(JdbcEditionScope scope) {
        this.scope = scope;
    }

    /** A live link resolved from its token: what the public view needs, edition and its name included. */
    public record ResolvedLink(
            String editionId, String editionNom, long id, String libelle, boolean fullNames, boolean restricted) {}

    /** The live links of the current edition, oldest first. */
    public List<AffichageMuralLink> list() {
        return scope.read("Failed to list the wall display links", connection -> {
            Map<Long, List<String>> emplacements = emplacementsByLink(connection);
            List<AffichageMuralLink> links = new ArrayList<>();
            try (PreparedStatement ps = scope.prepareScoped(connection, """
                    SELECT id, libelle, noms_complets, restreint, cree_le, dernier_acces_le
                    FROM lien_affichage_mural
                    WHERE edition_id = ? AND revoque_le IS NULL
                    ORDER BY cree_le, id""")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long id = rs.getLong("id");
                        links.add(link(rs, emplacements.getOrDefault(id, List.of())));
                    }
                }
            }
            return links;
        });
    }

    /** Writes a new link carrying {@code tokenHash}, and the emplacements it is restricted to. */
    public AffichageMuralLink insert(String libelle, boolean fullNames, List<String> emplacements, String tokenHash) {
        return scope.writeAndReturn("Failed to create a wall display link", connection -> {
            AffichageMuralLink link;
            try (PreparedStatement ps = scope.prepareScoped(connection, """
                    INSERT INTO lien_affichage_mural (edition_id, token_hash, libelle, noms_complets, restreint)
                    VALUES (?, ?, ?, ?, ?)
                    RETURNING id, libelle, noms_complets, restreint, cree_le, dernier_acces_le""")) {
                ps.setString(2, tokenHash);
                ps.setString(3, libelle);
                ps.setBoolean(4, fullNames);
                ps.setBoolean(5, !emplacements.isEmpty());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    link = link(rs, emplacements);
                }
            }
            try (PreparedStatement ps = scope.prepareScoped(connection, """
                    INSERT INTO lien_affichage_mural_emplacement (edition_id, lien_id, emplacement_id)
                    VALUES (?, ?, ?)""")) {
                for (String emplacement : emplacements) {
                    ps.setLong(2, link.id());
                    ps.setString(3, emplacement);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return link;
        });
    }

    /** Revokes a live link of the current edition; {@code false} when there is none by that id. */
    public boolean revoke(long id) {
        return scope.writeAndReturn("Failed to revoke wall display link " + id, connection -> {
            try (PreparedStatement ps = scope.prepareScoped(connection, """
                    UPDATE lien_affichage_mural SET revoque_le = now()
                    WHERE edition_id = ? AND id = ? AND revoque_le IS NULL""")) {
                ps.setLong(2, id);
                return ps.executeUpdate() > 0;
            }
        });
    }

    /**
     * The live link carrying this token hash, in any edition; {@code null}
     * when none does — unknown and revoked alike.
     */
    public ResolvedLink resolve(String tokenHash) {
        return scope.read("Failed to resolve a wall display token", connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT l.edition_id, e.nom AS edition_nom, l.id, l.libelle, l.noms_complets, l.restreint"
                            + " FROM lien_affichage_mural l JOIN edition e ON e.id = l.edition_id"
                            + " WHERE l.token_hash = ? AND l.revoque_le IS NULL")) {
                ps.setString(1, tokenHash);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next()
                            ? new ResolvedLink(
                                    rs.getString("edition_id"),
                                    rs.getString("edition_nom"),
                                    rs.getLong("id"),
                                    rs.getString("libelle"),
                                    rs.getBoolean("noms_complets"),
                                    rs.getBoolean("restreint"))
                            : null;
                }
            }
        });
    }

    /** The emplacements a link of the current edition is restricted to. */
    public List<String> emplacementsOf(long id) {
        return scope.read("Failed to read the emplacements of wall display link " + id, connection -> {
            List<String> emplacements = new ArrayList<>();
            try (PreparedStatement ps = scope.prepareScoped(connection, """
                    SELECT emplacement_id FROM lien_affichage_mural_emplacement
                    WHERE edition_id = ? AND lien_id = ? ORDER BY emplacement_id""")) {
                ps.setLong(2, id);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        emplacements.add(rs.getString(1));
                    }
                }
            }
            return emplacements;
        });
    }

    /**
     * Stamps the last read of a link. At most once a minute: the screen reads
     * every sixty seconds, and a write per read would be a write per read.
     */
    public void touch(long id) {
        scope.write("Failed to stamp the last read of wall display link " + id, connection -> {
            try (PreparedStatement ps = scope.prepareScoped(connection, """
                    UPDATE lien_affichage_mural SET dernier_acces_le = now()
                    WHERE edition_id = ? AND id = ?
                      AND (dernier_acces_le IS NULL OR dernier_acces_le < now() - INTERVAL '1 minute')""")) {
                ps.setLong(2, id);
                ps.executeUpdate();
            }
        });
    }

    private Map<Long, List<String>> emplacementsByLink(Connection connection) throws SQLException {
        Map<Long, List<String>> byLink = new LinkedHashMap<>();
        try (PreparedStatement ps = scope.prepareScoped(connection, """
                SELECT lien_id, emplacement_id FROM lien_affichage_mural_emplacement
                WHERE edition_id = ? ORDER BY lien_id, emplacement_id""")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    byLink.computeIfAbsent(rs.getLong(1), unused -> new ArrayList<>())
                            .add(rs.getString(2));
                }
            }
        }
        return byLink;
    }

    private static AffichageMuralLink link(ResultSet rs, List<String> emplacements) throws SQLException {
        return new AffichageMuralLink(
                rs.getLong("id"),
                rs.getString("libelle"),
                rs.getBoolean("noms_complets"),
                rs.getBoolean("restreint"),
                List.copyOf(emplacements),
                instant(rs.getTimestamp("cree_le")),
                instant(rs.getTimestamp("dernier_acces_le")));
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
