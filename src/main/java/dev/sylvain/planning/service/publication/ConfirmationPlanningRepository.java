package dev.sylvain.planning.service.publication;

import dev.sylvain.planning.domain.StatutConfirmation;
import dev.sylvain.planning.service.JdbcEditionScope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The {@code confirmation_planning} rows: who answered the published plan, and
 * who was reminded about it (issues #293 and #299).
 *
 * <p>A missing row is {@link StatutConfirmation#NON_VU}, so every read below
 * returns only what is actually stored and the callers fill the gap. That is
 * also why the reset of a republication is a {@link #reset} — deleting the rows
 * of the people whose schedule moved — rather than an update to a third
 * value.</p>
 */
@ApplicationScoped
public class ConfirmationPlanningRepository {

    @Inject
    JdbcEditionScope scope;

    /** One stored answer. {@code confirmeLe} and {@code relanceLe} coexist: a reminder can be answered. */
    public record Confirmation(String animateurId, StatutConfirmation statut, Instant confirmeLe, Instant relanceLe) {}

    /** Every stored answer of this edition, by animateur id. Absent means NON_VU. */
    public Map<String, Confirmation> byAnimateur() {
        return scope.read("Failed to load the planning confirmations", connection -> {
            try (PreparedStatement ps = scope.prepareScoped(connection, """
                    SELECT animateur_id, statut, confirme_le, relance_le
                    FROM confirmation_planning
                    WHERE edition_id = ?""");
                    ResultSet rs = ps.executeQuery()) {
                Map<String, Confirmation> confirmations = new LinkedHashMap<>();
                while (rs.next()) {
                    confirmations.put(
                            rs.getString("animateur_id"),
                            new Confirmation(
                                    rs.getString("animateur_id"),
                                    StatutConfirmation.valueOf(rs.getString("statut")),
                                    instant(rs.getTimestamp("confirme_le")),
                                    instant(rs.getTimestamp("relance_le"))));
                }
                return confirmations;
            }
        });
    }

    /**
     * One stored answer, by animateur id — the single-row read the espace does
     * on every load, kept apart from {@link #byAnimateur()} so it does not
     * scan the whole edition to answer about one person.
     */
    public Optional<Confirmation> byId(String animateurId) {
        return scope.read("Failed to load a planning confirmation", connection -> {
            try (PreparedStatement ps = scope.prepareScoped(connection, """
                    SELECT animateur_id, statut, confirme_le, relance_le
                    FROM confirmation_planning
                    WHERE edition_id = ? AND animateur_id = ?""")) {
                ps.setString(2, animateurId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.<Confirmation>empty();
                    }
                    return Optional.of(new Confirmation(
                            rs.getString("animateur_id"),
                            StatutConfirmation.valueOf(rs.getString("statut")),
                            instant(rs.getTimestamp("confirme_le")),
                            instant(rs.getTimestamp("relance_le"))));
                }
            }
        });
    }

    /**
     * Records « j'ai lu et je serai là », stamped at {@code confirmeLe}.
     *
     * <p>Re-clicking keeps the <b>first</b> date rather than moving it: the
     * question the admin asks is "since when do I know they will be there",
     * and a second click on a refreshed page must not answer it differently.
     * A reminder already sent stays visible in {@code relance_le}.</p>
     */
    public void confirmer(String animateurId, Instant confirmeLe) {
        scope.write("Failed to record the planning confirmation of an animateur", connection -> {
            try (PreparedStatement ps = scope.prepareScoped(connection, """
                    INSERT INTO confirmation_planning (edition_id, animateur_id, statut, confirme_le)
                    VALUES (?, ?, 'CONFIRME', ?)
                    ON CONFLICT (edition_id, animateur_id)
                    DO UPDATE SET statut = 'CONFIRME',
                    confirme_le = COALESCE(confirmation_planning.confirme_le, EXCLUDED.confirme_le)""")) {
                ps.setString(2, animateurId);
                ps.setTimestamp(3, Timestamp.from(confirmeLe));
                ps.executeUpdate();
            }
        });
    }

    /**
     * Marks a reminder as sent, without ever overwriting a confirmation: the
     * {@code WHERE} of the upsert leaves a CONFIRME row alone, so a race
     * between the nightly job and a click cannot lose the answer.
     */
    public void recordReminder(String animateurId, Instant relanceLe) {
        scope.write("Failed to record a confirmation reminder", connection -> {
            try (PreparedStatement ps = scope.prepareScoped(connection, """
                    INSERT INTO confirmation_planning (edition_id, animateur_id, statut, relance_le)
                    VALUES (?, ?, 'RELANCE', ?)
                    ON CONFLICT (edition_id, animateur_id)
                    DO UPDATE SET statut = 'RELANCE', relance_le = EXCLUDED.relance_le
                    WHERE confirmation_planning.statut <> 'CONFIRME'""")) {
                ps.setString(2, animateurId);
                ps.setTimestamp(3, Timestamp.from(relanceLe));
                ps.executeUpdate();
            }
        });
    }

    /**
     * Sends these animateurs back to NON_VU — what a republication does to the
     * people whose schedule changed, and to nobody else.
     */
    public void reset(Collection<String> animateurIds) {
        if (animateurIds.isEmpty()) {
            return;
        }
        scope.write("Failed to reset the planning confirmations", connection -> {
            try (PreparedStatement ps = scope.prepareScoped(
                    connection, "DELETE FROM confirmation_planning WHERE edition_id = ? AND animateur_id = ?")) {
                for (String animateurId : animateurIds) {
                    ps.setString(2, animateurId);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        });
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
