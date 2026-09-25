package dev.sylvain.planning.service.notification;

import dev.sylvain.planning.service.JdbcEditionScope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * What the scheduled jobs have already sent, and the only thing standing
 * between them and a mailbox full of duplicates.
 *
 * <p><b>The primary key is the mechanism.</b> A job does not ask "have I
 * already done this?" and then act — two runs would both read "no" and both
 * send. It <i>claims</i> the key with an {@code INSERT … ON CONFLICT DO
 * NOTHING} and sends only if the insert wrote a row. The database arbitrates,
 * once, and a crash between the claim and the send costs one missed message
 * rather than a duplicated one — the right way round for a reminder somebody
 * also reads in their espace.</p>
 *
 * <p>Rows carrying a {@code libelle} are also alerts shown on the Notifications
 * screen; the others are pure locks. Either way <b>nothing nominative is
 * stored</b>: an animateur id at most, never a name and never an address —
 * the screen resolves the identity from the referential when it reads (see
 * {@code docs/rgpd.md}).</p>
 */
@ApplicationScoped
public class JournalNotificationsRepository {

    /** Kinds of scheduled notification, and the shape of the key each one claims. */
    public enum Type {

        /** J-1 reminder (issue #298). Key: {@code animateurId|date}. */
        RAPPEL_VEILLE,

        /** A J-1 reminder that could not leave: no address on the fiche. Key: {@code animateurId|date}. */
        RAPPEL_VEILLE_INJOIGNABLE,

        /**
         * Reminder of a silent animateur (issue #299), claimed by the night
         * and by the hand alike. Key: {@code animateurId|publicationInstant}.
         */
        RELANCE_CONFIRMATION,

        /**
         * The night has attempted this reminder, whatever became of it. Same
         * key. Held apart from {@link #RELANCE_CONFIRMATION} because a failed
         * send gives that one back — so « Relancer maintenant » stays possible
         * — while the night must still not try again every hour.
         */
        RELANCE_NUIT_TENTEE,

        /**
         * A reminder that could not leave: no address on the fiche, or a send
         * that failed. Same key.
         */
        RELANCE_INJOIGNABLE,

        /** A swap request has been waiting too long (issue #300). Key: the demande id. */
        ALERTE_ECHANGE
    }

    /** Severity of an alert, matching what the Notifications screen already displays. */
    public enum Severite {
        INFO,
        WARNING,
        ALERTE
    }

    /** One alert of the Notifications screen, newest first. */
    public record Alerte(
            String type, String cle, Instant declencheLe, String libelle, String severite, String animateurId) {}

    private final JdbcEditionScope scope;

    @Inject
    public JournalNotificationsRepository(JdbcEditionScope scope) {
        this.scope = scope;
    }

    /**
     * Claims {@code cle} for {@code type}, without leaving anything on the
     * Notifications screen — the plain idempotence lock.
     *
     * @return true when this call is the one that claimed it, and therefore the
     *         one that must send
     */
    public boolean claim(Type type, String cle, String animateurId) {
        return claim(type, cle, animateurId, null, null);
    }

    /**
     * Same claim, plus the sentence the Notifications screen shows.
     *
     * @param libelle written for an organiser and <b>never nominative</b>: it
     *                is stored, so it says what happened, and the identity —
     *                when there is one — travels as {@code animateurId}
     */
    public boolean claim(Type type, String cle, String animateurId, String libelle, Severite severite) {
        return scope.writeAndReturn("Failed to record a scheduled notification", connection -> {
            try (PreparedStatement ps = scope.prepareScoped(connection, """
                    INSERT INTO notification_planifiee (edition_id, type, cle, declenche_le, libelle,
                    severite, animateur_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (edition_id, type, cle) DO NOTHING""")) {
                ps.setString(2, type.name());
                ps.setString(3, cle);
                ps.setTimestamp(4, Timestamp.from(Instant.now()));
                ps.setString(5, libelle);
                ps.setString(6, severite == null ? null : severite.name());
                ps.setString(7, animateurId);
                return ps.executeUpdate() == 1;
            }
        });
    }

    /**
     * Gives {@code cle} back, so that the work it reserved can be attempted
     * again. For the one case where the claim was taken and the send did not
     * happen: holding the key then would mean nobody ever retries, neither the
     * hand (refused as already reminded) nor the night (which writes only to
     * the people it has no row for).
     *
     * <p>Not a general-purpose delete: a claim is released by the very call
     * that took it, in the failure path it opened.</p>
     */
    public void release(Type type, String cle) {
        scope.write("Failed to release a scheduled notification", connection -> {
            try (PreparedStatement ps = scope.prepareScoped(
                    connection, "DELETE FROM notification_planifiee WHERE edition_id = ? AND type = ? AND cle = ?")) {
                ps.setString(2, type.name());
                ps.setString(3, cle);
                ps.executeUpdate();
            }
        });
    }

    /**
     * The alerts of this edition, newest first, capped <b>per type</b> so that
     * one noisy kind cannot starve another.
     *
     * <p>A flat « newest N » was wrong, not merely imprecise. An edition with
     * twenty addressless fiches raises twenty {@code RAPPEL_VEILLE_INJOIGNABLE}
     * rows <i>every evening</i>, so within a few days they fill any global cap
     * and push the {@code ALERTE_ECHANGE} rows off the screen — the only ones
     * that ask the admin for a <b>decision</b>, and whose only way out is this
     * very screen. The window function gives each type its own quota; the
     * merged result is still read newest-first.</p>
     */
    public List<Alerte> alertes(int limite) {
        String sql = """
                    SELECT type, cle, declenche_le, libelle, severite, animateur_id
                    FROM (
                        SELECT type, cle, declenche_le, libelle, severite, animateur_id,
                               ROW_NUMBER() OVER (PARTITION BY type ORDER BY declenche_le DESC) AS rang
                        FROM notification_planifiee
                        WHERE edition_id = ? AND libelle IS NOT NULL
                    ) classees
                    WHERE rang <= ?
                    ORDER BY declenche_le DESC""";
        return scope.read("Failed to load the scheduled notification alerts", connection -> {
            try (PreparedStatement ps = scope.prepareScoped(connection, sql)) {
                ps.setInt(2, limite);
                try (ResultSet rs = ps.executeQuery()) {
                    List<Alerte> alertes = new ArrayList<>();
                    while (rs.next()) {
                        alertes.add(new Alerte(
                                rs.getString("type"),
                                rs.getString("cle"),
                                instant(rs.getTimestamp("declenche_le")),
                                rs.getString("libelle"),
                                rs.getString("severite"),
                                rs.getString("animateur_id")));
                    }
                    return alertes;
                }
            }
        });
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
