package dev.sylvain.planning.service.backup;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import javax.sql.DataSource;

/**
 * The single row of {@code backup_settings}: the suspend switch and the report
 * of the last attempt.
 *
 * <p>Instance-wide, and therefore <b>not</b> routed through
 * {@code JdbcEditionScope}: a {@code pg_dump} takes the whole cluster, every
 * edition included, so binding this table to the request's edition would be
 * binding it to something it does not depend on. The table carries no
 * {@code edition_id} for the same reason (migration {@code V56}).</p>
 */
@ApplicationScoped
public class BackupRepository {

    @Inject
    DataSource dataSource;

    public boolean isActive() {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement("SELECT actif FROM backup_settings WHERE id = TRUE");
                ResultSet rows = statement.executeQuery()) {
            return rows.next() && rows.getBoolean("actif");
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read the automatic backup settings", e);
        }
    }

    public void setActive(boolean active) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO backup_settings (id, actif) VALUES (TRUE, ?)
                        ON CONFLICT (id) DO UPDATE SET actif = EXCLUDED.actif""")) {
            statement.setBoolean(1, active);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save the automatic backup settings", e);
        }
    }

    public BackupRun lastRun() {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT derniere_tentative, dernier_succes, dernier_fichier, dernier_message
                        FROM backup_settings WHERE id = TRUE""");
                ResultSet rows = statement.executeQuery()) {
            if (!rows.next()) {
                return BackupRun.never();
            }
            Timestamp attemptedAt = rows.getTimestamp("derniere_tentative");
            if (attemptedAt == null) {
                return BackupRun.never();
            }
            return new BackupRun(
                    attemptedAt.toInstant(),
                    rows.getBoolean("dernier_succes"),
                    rows.getString("dernier_fichier"),
                    rows.getString("dernier_message"));
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read the last automatic backup", e);
        }
    }

    /**
     * Records the attempt and moves the failure streak in the same statement:
     * a success clears it and dates the last success, a failure extends it.
     * The streak as it stood <b>before</b> comes from the self-join, which
     * PostgreSQL reads in the statement's snapshot — the pre-update row — so
     * one statement tells a first success from a recovery, with no window
     * between a read and a write.
     * {@link BackupRun#never()} resets everything — what the tests start from.
     *
     * @return the streak after this attempt, and the one it replaced
     */
    public BackupStreak saveLastRun(BackupRun run) {
        return saveLastRun(run, 0);
    }

    /**
     * Same, folding in failures that happened while this very table could not
     * be written: they extend the streak like any other, and they count as the
     * streak "before" — so the success that follows them is a recovery.
     *
     * @param unrecordedFailures failed attempts the database never saw
     */
    public BackupStreak saveLastRun(BackupRun run, int unrecordedFailures) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        UPDATE backup_settings AS apres
                        SET derniere_tentative = ?, dernier_succes = ?, dernier_fichier = ?, dernier_message = ?,
                            echecs_consecutifs = CASE WHEN ? THEN apres.echecs_consecutifs + 1 + ? ELSE 0 END,
                            dernier_succes_le = CASE WHEN ? THEN ? WHEN ? THEN NULL ELSE apres.dernier_succes_le END
                        FROM backup_settings AS avant
                        WHERE apres.id = TRUE AND avant.id = TRUE
                        RETURNING apres.echecs_consecutifs, apres.dernier_succes_le,
                                  avant.echecs_consecutifs + ? AS echecs_avant""")) {
            Timestamp attemptedAt = run.attemptedAt() == null ? null : Timestamp.from(run.attemptedAt());
            boolean failed = run.ranAtLeastOnce() && !run.succeeded();
            statement.setTimestamp(1, attemptedAt);
            statement.setBoolean(2, run.succeeded());
            statement.setString(3, run.file());
            statement.setString(4, run.message());
            int folded = run.ranAtLeastOnce() ? unrecordedFailures : 0;
            statement.setBoolean(5, failed);
            statement.setInt(6, folded);
            statement.setBoolean(7, run.succeeded());
            statement.setTimestamp(8, attemptedAt);
            statement.setBoolean(9, !run.ranAtLeastOnce());
            statement.setInt(10, folded);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return BackupStreak.none();
                }
                Timestamp lastSuccess = rows.getTimestamp("dernier_succes_le");
                return new BackupStreak(
                        rows.getInt("echecs_consecutifs"),
                        rows.getInt("echecs_avant"),
                        lastSuccess == null ? null : lastSuccess.toInstant());
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to record the last automatic backup", e);
        }
    }
}
