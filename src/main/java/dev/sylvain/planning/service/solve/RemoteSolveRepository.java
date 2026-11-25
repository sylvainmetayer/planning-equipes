package dev.sylvain.planning.service.solve;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * What the application must still know after a restart about a solve run
 * elsewhere (ADR 0086): the solve a job left on a worker.
 *
 * <p>The table belongs to the instance, not to an edition: a restart looks for
 * it before any request names one.</p>
 */
@ApplicationScoped
public class RemoteSolveRepository {

    /** A solve left on a worker: its id there, and the pseudonym table as JSON. */
    record PendingSolve(String solveId, String realIds) {}

    private final DataSource dataSource;

    @Inject
    public RemoteSolveRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    void savePending(String jobId, String solveId, String realIds, Instant now) {
        update("""
                INSERT INTO resolution_distante (job_id, solve_id, identifiants, cree_le) VALUES (?, ?, ?, ?)
                ON CONFLICT (job_id) DO UPDATE SET solve_id = EXCLUDED.solve_id,
                    identifiants = EXCLUDED.identifiants, cree_le = EXCLUDED.cree_le""", jobId, solveId, realIds, Timestamp.from(now));
    }

    Optional<PendingSolve> findPending(String jobId) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT solve_id, identifiants FROM resolution_distante WHERE job_id = ?")) {
            statement.setString(1, jobId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next()
                        ? Optional.of(new PendingSolve(rows.getString("solve_id"), rows.getString("identifiants")))
                        : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read a remote solve", e);
        }
    }

    void deletePending(String jobId) {
        update("DELETE FROM resolution_distante WHERE job_id = ?", jobId);
    }

    private void update(String sql, Object... values) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) {
                statement.setObject(i + 1, values[i]);
            }
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to write the remote solve state", e);
        }
    }
}
