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
 * elsewhere (ADR 0086): the rented machine that is up, and the solve a job
 * left on a worker.
 *
 * <p>Both tables belong to the instance, not to an edition: one machine serves
 * every edition, and a restart looks for it before any request names one.</p>
 */
@ApplicationScoped
public class RemoteSolveRepository {

    /**
     * A machine started through the hook.
     *
     * @param nonce        what its secret is derived from, with the hook's
     * @param url          where its worker answers, once ready
     * @param fingerprint  the SHA-256 of its certificate, once ready
     * @param readyAt      {@code null} while it is being prepared
     */
    record Machine(
            long id,
            String version,
            String nonce,
            String url,
            String fingerprint,
            Instant startedAt,
            Instant readyAt,
            Instant lastActivity) {}

    /** A solve left on a worker: its id there, and the pseudonym table as JSON. */
    record PendingSolve(String solveId, String realIds) {}

    private final DataSource dataSource;

    @Inject
    public RemoteSolveRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** The machine that is up, being prepared or ready. */
    Optional<Machine> liveMachine() {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT id, version, alea, url, empreinte, demarree_le, prete_le, derniere_activite
                        FROM machine_calcul WHERE arretee_le IS NULL""");
                ResultSet rows = statement.executeQuery()) {
            if (!rows.next()) {
                return Optional.empty();
            }
            Timestamp ready = rows.getTimestamp("prete_le");
            return Optional.of(new Machine(
                    rows.getLong("id"),
                    rows.getString("version"),
                    rows.getString("alea"),
                    rows.getString("url"),
                    rows.getString("empreinte"),
                    rows.getTimestamp("demarree_le").toInstant(),
                    ready == null ? null : ready.toInstant(),
                    rows.getTimestamp("derniere_activite").toInstant()));
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read the compute machine", e);
        }
    }

    void openMachine(String version, String nonce, Instant now) {
        update(
                "INSERT INTO machine_calcul (version, alea, demarree_le, derniere_activite) VALUES (?, ?, ?, ?)",
                version,
                nonce,
                Timestamp.from(now),
                Timestamp.from(now));
    }

    void markReady(long id, String url, String fingerprint, Instant now) {
        update(
                "UPDATE machine_calcul SET url = ?, empreinte = ?, prete_le = ?, derniere_activite = ? WHERE id = ?",
                url,
                fingerprint,
                Timestamp.from(now),
                Timestamp.from(now),
                id);
    }

    /** Something used the machine: the idle countdown starts again. */
    void touch(Instant now) {
        update("UPDATE machine_calcul SET derniere_activite = ? WHERE arretee_le IS NULL", Timestamp.from(now));
    }

    void closeMachine(long id, Instant now) {
        update("UPDATE machine_calcul SET arretee_le = ? WHERE id = ?", Timestamp.from(now), id);
    }

    /**
     * The machine hours of the month {@code now} is in, each machine rounded
     * up to the hour — what an hourly invoice counts.
     */
    long billedHoursOfMonth(Instant now) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT COALESCE(SUM(CEIL(EXTRACT(EPOCH FROM (COALESCE(arretee_le, ?) - demarree_le)) / 3600.0)), 0)
                        FROM machine_calcul
                        WHERE demarree_le >= date_trunc('month', ?::timestamptz)""")) {
            statement.setTimestamp(1, Timestamp.from(now));
            statement.setTimestamp(2, Timestamp.from(now));
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to count the compute machine's hours", e);
        }
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

    /** Whether any job has a solve left on a worker. */
    boolean anyPending() {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM resolution_distante LIMIT 1");
                ResultSet rows = statement.executeQuery()) {
            return rows.next();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read the remote solves", e);
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
