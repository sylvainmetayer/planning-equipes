package dev.sylvain.planning.service.journal;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;

/**
 * The {@code journal_connexion} table: the admin logins, failures and
 * lockouts, append-only, read newest first.
 *
 * <p>Instance-wide, and therefore <b>not</b> routed through
 * {@code JdbcEditionScope}, like the webhook tables: a login happens before
 * any request names an edition, and the screen shows it whatever edition is
 * chosen.</p>
 */
@ApplicationScoped
public class LoginJournalRepository {

    /** Hard ceiling on one page: a screen, not an export. */
    static final int LIMITE_MAX = 500;

    private final DataSource dataSource;

    @Inject
    public LoginJournalRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Appends the lines of one attempt, in order, in one transaction. */
    public void append(List<LoginJournalEntry> entries) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO journal_connexion (survenu_le, evenement, adresse) VALUES (?, ?, ?)")) {
            for (LoginJournalEntry entry : entries) {
                ps.setTimestamp(1, Timestamp.from(entry.survenuLe()));
                ps.setString(2, entry.evenement().name());
                ps.setString(3, entry.adresse());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to record an admin login", e);
        }
    }

    /**
     * One page, newest first, after the line {@code before} — the same keyset
     * cursor as the history of actions. A cursor naming no line answers an
     * empty page: only the nightly purge removes one, and it takes every older
     * line with it.
     */
    public List<LoginJournalEntry> page(Long before, int limite) {
        int plafond = Math.clamp(limite, 1, LIMITE_MAX);
        try (Connection connection = dataSource.getConnection()) {
            Timestamp cursor = null;
            if (before != null) {
                try (PreparedStatement ps =
                        connection.prepareStatement("SELECT survenu_le FROM journal_connexion WHERE id = ?")) {
                    ps.setLong(1, before);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            return List.of();
                        }
                        cursor = rs.getTimestamp("survenu_le");
                    }
                }
            }
            try (PreparedStatement ps = connection.prepareStatement("""
                    SELECT id, survenu_le, evenement, adresse
                    FROM journal_connexion
                    WHERE (survenu_le, id) < (COALESCE(?::timestamptz, 'infinity'), COALESCE(?::bigint, 9223372036854775807))
                    ORDER BY survenu_le DESC, id DESC
                    LIMIT ?""")) {
                ps.setTimestamp(1, cursor);
                if (cursor == null) {
                    ps.setNull(2, Types.BIGINT);
                } else {
                    ps.setLong(2, before);
                }
                ps.setInt(3, plafond);
                try (ResultSet rs = ps.executeQuery()) {
                    List<LoginJournalEntry> entries = new ArrayList<>();
                    while (rs.next()) {
                        entries.add(new LoginJournalEntry(
                                rs.getLong("id"),
                                rs.getTimestamp("survenu_le").toInstant(),
                                LoginJournalEntry.Evenement.valueOf(rs.getString("evenement")),
                                rs.getString("adresse")));
                    }
                    return entries;
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read the admin logins", e);
        }
    }

    /** Drops what is older than {@code cutoff}; returns how many lines went. */
    public int purgeBefore(Instant cutoff) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps =
                        connection.prepareStatement("DELETE FROM journal_connexion WHERE survenu_le < ?")) {
            ps.setTimestamp(1, Timestamp.from(cutoff));
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to purge the admin logins", e);
        }
    }
}
