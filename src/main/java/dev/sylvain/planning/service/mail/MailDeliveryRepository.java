package dev.sylvain.planning.service.mail;

import dev.sylvain.planning.service.JdbcEditionScope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The {@code envoi_mail} journal: one row per mail to an animateur, carrying
 * an id, a kind, an outcome, a failure category and a date — never an address
 * nor a line of the message (see {@code docs/rgpd.md}).
 */
@ApplicationScoped
public class MailDeliveryRepository {

    private final JdbcEditionScope scope;

    @Inject
    public MailDeliveryRepository(JdbcEditionScope scope) {
        this.scope = scope;
    }

    /**
     * The last mail to each animateur <b>since their address last changed</b>.
     *
     * <p>A send older than the address says nothing about the one the fiche
     * holds now: changing the address is what lifts a failure, and nothing
     * else does — editing a skill or a date of birth leaves the refused
     * address where it was. So the join keeps the rows written at or after
     * {@code animateur.email_modifie_le}, which only an address change moves,
     * and nothing else.</p>
     */
    public Map<String, LastDelivery> latestByAnimateur() {
        return scope.read("Failed to read the last mail sent to each animateur", connection -> {
            try (PreparedStatement ps = scope.prepareScoped(connection, """
                    SELECT DISTINCT ON (m.animateur_id)
                           m.animateur_id, m.type, m.statut, m.categorie_echec, m.envoye_le
                    FROM envoi_mail m
                    JOIN animateur a ON a.edition_id = m.edition_id AND a.id = m.animateur_id
                    WHERE m.edition_id = ? AND m.envoye_le >= a.email_modifie_le
                    ORDER BY m.animateur_id, m.envoye_le DESC, m.id DESC""");
                    ResultSet rs = ps.executeQuery()) {
                Map<String, LastDelivery> latest = new LinkedHashMap<>();
                while (rs.next()) {
                    latest.put(
                            rs.getString("animateur_id"),
                            new LastDelivery(
                                    rs.getString("type"),
                                    rs.getString("statut"),
                                    rs.getString("categorie_echec"),
                                    rs.getTimestamp("envoye_le").toInstant()));
                }
                return latest;
            }
        });
    }

    /**
     * The id of the newest line written for this animateur, {@code 0} when
     * there is none: a marker to tell, afterwards, which lines a send wrote.
     */
    public long lastId(String animateurId) {
        return scope.read("Failed to read the last mail line of an animateur", connection -> {
            try (PreparedStatement ps = scope.prepareScoped(connection, """
                    SELECT COALESCE(MAX(id), 0) FROM envoi_mail WHERE edition_id = ? AND animateur_id = ?""")) {
                ps.setString(2, animateurId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0L;
                }
            }
        });
    }

    /** Whether a line saying a {@code kind} mail left was written for this animateur after {@code afterId}. */
    public boolean sentAfter(String animateurId, MailKind kind, long afterId) {
        return scope.read("Failed to read whether a mail left", connection -> {
            try (PreparedStatement ps = scope.prepareScoped(connection, """
                    SELECT 1 FROM envoi_mail
                    WHERE edition_id = ? AND animateur_id = ? AND type = ? AND statut = 'ENVOYE' AND id > ?
                    LIMIT 1""")) {
                ps.setString(2, animateurId);
                ps.setString(3, kind.name());
                ps.setLong(4, afterId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
    }

    /**
     * Appends one line. {@code failure} is {@code null} unless {@code outcome}
     * is ECHEC. Dated by the database, like {@code animateur.modifie_le} it is
     * compared with: two clocks would let a skew decide whether a correction
     * lifted a failure.
     */
    public void record(String animateurId, MailKind kind, MailOutcome outcome, MailFailure failure) {
        scope.write("Failed to record the outcome of a mail", connection -> {
            try (PreparedStatement ps = scope.prepareScoped(connection, """
                    INSERT INTO envoi_mail (edition_id, animateur_id, type, statut, categorie_echec)
                    VALUES (?, ?, ?, ?, ?)""")) {
                ps.setString(2, animateurId);
                ps.setString(3, kind.name());
                ps.setString(4, outcome.name());
                ps.setString(5, failure == null ? null : failure.name());
                ps.executeUpdate();
            }
        });
    }
}
