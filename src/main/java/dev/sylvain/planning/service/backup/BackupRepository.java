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

    public void saveLastRun(BackupRun run) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        UPDATE backup_settings
                        SET derniere_tentative = ?, dernier_succes = ?, dernier_fichier = ?, dernier_message = ?
                        WHERE id = TRUE""")) {
            statement.setTimestamp(1, run.attemptedAt() == null ? null : Timestamp.from(run.attemptedAt()));
            statement.setBoolean(2, run.succeeded());
            statement.setString(3, run.file());
            statement.setString(4, run.message());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to record the last automatic backup", e);
        }
    }
}
