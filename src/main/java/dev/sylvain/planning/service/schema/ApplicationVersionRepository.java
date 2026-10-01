package dev.sylvain.planning.service.schema;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * {@code version_applicative}, the application versions that opened this
 * database (migration {@code V115}).
 *
 * <p>Instance-wide, and therefore <b>not</b> routed through
 * {@code JdbcEditionScope}, like {@code BackupRepository}: the table describes
 * the database every edition lives in, and it is read at boot, where no
 * edition exists to bind it to.</p>
 */
@ApplicationScoped
public class ApplicationVersionRepository {

    private final DataSource dataSource;

    @Inject
    public ApplicationVersionRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Adds a line unless the latest one already names {@code version}: one line
     * per change of version, so a version that comes back after another —
     * the rollback this history exists to show — gets a line of its own.
     *
     * <p>One statement rather than a read then a write: two instances booting
     * the same version at once is not a case this table needs to argue about,
     * but there is no reason to open the window either.</p>
     *
     * @return {@code true} when a line was added
     */
    public boolean recordStart(String version, String latestMigration, Instant now) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO version_applicative (version, premier_demarrage, derniere_migration)
                        SELECT ?, ?, ?
                        WHERE ? IS DISTINCT FROM
                              (SELECT version FROM version_applicative ORDER BY id DESC LIMIT 1)""")) {
            statement.setString(1, version);
            statement.setTimestamp(2, Timestamp.from(now));
            statement.setString(3, latestMigration);
            statement.setString(4, version);
            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to record the application version", e);
        }
    }

    /** Every line, the latest first: the Débogage page reads the current version at the top. */
    public List<ApplicationVersion> list() {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT version, premier_demarrage, derniere_migration
                        FROM version_applicative ORDER BY id DESC""");
                ResultSet rows = statement.executeQuery()) {
            List<ApplicationVersion> versions = new ArrayList<>();
            while (rows.next()) {
                versions.add(new ApplicationVersion(
                        rows.getString("version"),
                        rows.getTimestamp("premier_demarrage").toInstant(),
                        rows.getString("derniere_migration")));
            }
            return versions;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read the application versions", e);
        }
    }

    /**
     * The version that first started on {@code migration} as its latest one —
     * the one that applied it, short of a start recorded by a version that
     * found it already there, which can only come later. Empty when no line
     * names it: a database migrated before this table existed.
     */
    public Optional<String> firstVersionAt(String migration) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT version FROM version_applicative
                        WHERE derniere_migration = ? ORDER BY id LIMIT 1""")) {
            statement.setString(1, migration);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(rows.getString("version")) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read the application versions", e);
        }
    }
}
