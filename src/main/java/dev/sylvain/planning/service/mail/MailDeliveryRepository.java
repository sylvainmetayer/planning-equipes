package dev.sylvain.planning.service.mail;

import dev.sylvain.planning.service.JdbcEditionScope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * The outcome of every mail sent to an animateur ({@code envoi_mail}): what
 * tells « the mail failed » apart from « the person never answered », which
 * used to look the same on the Animateurs page.
 *
 * <p>Written by {@link MailMetrics}, the one place every mail leaves through,
 * so no sender can forget it. <b>Nothing nominative is stored</b>, like
 * {@code notification_planifiee}: the animateur's id, the template's name, the
 * state, the category of a failure and the date — never the address, never
 * the content, never the relay's message. The identity is joined from the
 * referential by whoever reads.</p>
 *
 * <p>Kept as long as the history ({@code JOURNAL_RETENTION}): the nightly
 * sweep drops older rows across every edition, and an edition's rows go with
 * it.</p>
 */
@ApplicationScoped
public class MailDeliveryRepository implements MailDeliveryLog {

    private final JdbcEditionScope scope;

    @Inject
    public MailDeliveryRepository(JdbcEditionScope scope) {
        this.scope = scope;
    }

    @Override
    public void save(String animateurId, String template, MailDeliveryOutcome outcome) {
        scope.write("Failed to record a mail delivery", connection -> {
            // envoye_le is the database's now(), the clock the fiche's
            // modifie_le is written with: the two are compared to tell
            // whether the fiche was edited after a failure.
            try (PreparedStatement ps = scope.prepareScoped(connection, """
                    INSERT INTO envoi_mail (edition_id, animateur_id, type, statut, categorie_echec)
                    VALUES (?, ?, ?, ?, ?)""")) {
                ps.setString(2, animateurId);
                ps.setString(3, MailMetrics.label(template));
                ps.setString(4, outcome.status().name());
                ps.setString(
                        5,
                        outcome.category() == null ? null : outcome.category().name());
                ps.executeUpdate();
            }
        });
    }

    /** Each animateur's latest send in this edition, by id — what the acknowledgement column reads. */
    public Map<String, MailDelivery> latestByAnimateur() {
        String sql = """
                SELECT DISTINCT ON (animateur_id) animateur_id, type, statut, categorie_echec, envoye_le
                FROM envoi_mail
                WHERE edition_id = ?
                ORDER BY animateur_id, envoye_le DESC, id DESC""";
        return scope.read("Failed to read the mail deliveries", connection -> {
            Map<String, MailDelivery> latest = new HashMap<>();
            try (PreparedStatement ps = scope.prepareScoped(connection, sql);
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    MailDelivery delivery = read(rs);
                    latest.put(delivery.animateurId(), delivery);
                }
            }
            return latest;
        });
    }

    /**
     * Drops the rows older than {@code cutoff}, across every edition at once.
     *
     * <p>Not edition-scoped, for the reason {@code JournalActionRepository}
     * gives for its own sweep: the nightly job that runs it has no edition of
     * its own, and the retention is a property of the table. Named in
     * {@code EXCEPTIONS_ASSUMEES} of {@code IsolationEditionStructurelleTest}.</p>
     *
     * @return how many rows were dropped
     */
    public int purgeBefore(Instant cutoff) {
        return scope.writeAndReturn("Failed to purge the mail deliveries", connection -> {
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM envoi_mail WHERE envoye_le < ?")) {
                ps.setTimestamp(1, Timestamp.from(cutoff));
                return ps.executeUpdate();
            }
        });
    }

    private static MailDelivery read(ResultSet rs) throws SQLException {
        String category = rs.getString("categorie_echec");
        return new MailDelivery(
                rs.getString("animateur_id"),
                rs.getString("type"),
                MailDeliveryOutcome.Status.valueOf(rs.getString("statut")),
                category == null ? null : MailFailureCategory.valueOf(category),
                rs.getTimestamp("envoye_le").toInstant());
    }
}
