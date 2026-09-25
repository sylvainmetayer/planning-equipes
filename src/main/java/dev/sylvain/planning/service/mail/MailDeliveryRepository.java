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
     * The last mail to each animateur <b>since their fiche last changed</b>.
     *
     * <p>A send older than the fiche says nothing about the address it holds
     * now: correcting the address is what lifts a failure, and a
     * modification is the only thing the fiche records. So the join keeps the
     * rows written at or after {@code animateur.modifie_le} and nothing
     * else.</p>
     */
    public Map<String, LastDelivery> latestByAnimateur() {
        return scope.read("Failed to read the last mail sent to each animateur", connection -> {
            try (PreparedStatement ps = scope.prepareScoped(connection, """
                    SELECT DISTINCT ON (m.animateur_id)
                           m.animateur_id, m.type, m.statut, m.categorie_echec, m.envoye_le
                    FROM envoi_mail m
                    JOIN animateur a ON a.edition_id = m.edition_id AND a.id = m.animateur_id
                    WHERE m.edition_id = ? AND m.envoye_le >= a.modifie_le
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
