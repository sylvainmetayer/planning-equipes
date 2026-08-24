package dev.sylvain.planning.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import javax.sql.DataSource;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The one place that opens a connection, binds the current edition and turns a
 * {@link SQLException} into something a caller can act on.
 *
 * <p>Five services carried their own byte-for-byte copy of
 * {@link #prepareScoped}, and nine wrote the
 * {@code setAutoCommit(false)}/{@code commit}/{@code rollback} dance by hand.
 * That boilerplate is not a cosmetic problem: it is exactly where the
 * cross-edition ninja bug got in. The author needed a placeholder in a
 * position the local helper did not offer, dropped to
 * {@code connection.prepareStatement} — and the {@code edition_id} predicate
 * went with it, silently, for two releases.</p>
 *
 * <p>Everything is partitioned by edition (see {@code docs/decisions/0001-cloisonnement-par-edition.md}), so
 * the edition must be the hardest thing in this codebase to forget, not the
 * easiest. {@code IsolationEditionStructurelleTest} closes the loop: it reads
 * the SQL of every class that touches the database and fails on any statement
 * against a business table without an {@code edition_id} predicate, unless
 * that statement is on a short list of deliberate exceptions.</p>
 */
@ApplicationScoped
public class JdbcEditionScope {

    @Inject
    DataSource dataSource;

    @Inject
    EditionContext editionContext;

    /** Work on a borrowed connection that yields a result. */
    @FunctionalInterface
    public interface Query<T> {
        T execute(Connection connection) throws SQLException;
    }

    /** Work on a borrowed connection that yields nothing. */
    @FunctionalInterface
    public interface Command {
        void execute(Connection connection) throws SQLException;
    }

    /**
     * Runs {@code lecture} on a borrowed connection.
     *
     * @param failure what to say when the database refuses — a sentence naming
     *              the business operation, not the SQL, since it ends up in a
     *              log an operator reads
     */
    public <T> T read(String failure, Query<T> statement) {
        try (Connection connection = dataSource.getConnection()) {
            return statement.execute(connection);
        } catch (SQLException e) {
            throw new IllegalStateException(failure, e);
        }
    }

    /**
     * Runs {@code ecriture} in a transaction, committing on success and rolling
     * back on any failure. Written once so no caller has to remember the order
     * of the three calls, nor that the rollback belongs in the inner catch.
     */
    public void write(String failure, Command command) {
        writeAndReturn(failure, connection -> {
            command.execute(connection);
            return null;
        });
    }

    /**
     * Same, for a transaction whose result the caller needs — typically the
     * number of rows it wrote.
     *
     * <p>{@code autoCommit} is restored in a {@code finally}: the connection
     * goes back to the pool, and the next borrower must not inherit a
     * transaction mode it did not ask for.</p>
     */
    public <T> T writeAndReturn(String failure, Query<T> statement) {
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommitPrecedent = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                T result = statement.execute(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(autoCommitPrecedent);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(failure, e);
        }
    }

    /**
     * Prepares {@code sql} with the current edition already bound to its
     * <b>first</b> placeholder — so write the {@code edition_id = ?} predicate
     * (or the {@code edition_id} column of an INSERT) first, and bind the rest
     * from index 2. An UPDATE whose SET clause carries a placeholder of its own
     * must therefore put {@code edition_id = ?} first in its WHERE and count
     * from there.
     */
    public PreparedStatement prepareScoped(Connection connection, String sql) throws SQLException {
        PreparedStatement ps = connection.prepareStatement(sql);
        try {
            ps.setString(1, editionId());
            return ps;
        } catch (SQLException | RuntimeException e) {
            ps.close();
            throw e;
        }
    }

    /** The edition every statement above is scoped to. */
    public String editionId() {
        return editionContext.editionIdCourant();
    }

    /**
     * Does {@code table} hold this id in the current edition?
     *
     * <p>Written once here rather than in each repository: the probe is the
     * same statement every time, and the one thing that must never vary — the
     * {@code edition_id} predicate — should not be retyped eight times.</p>
     *
     * <p>{@code table} comes from a repository's own call sites, never from
     * user input: a table name cannot be bound as a parameter, so it is
     * concatenated, while the id that varies travels bound. Same reasoning for
     * every {@code nosemgrep} below (see {@code docs/securite.md}).</p>
     */
    public boolean exists(String table, String id) {
        return exists(table, ps -> ps.setString(2, id), table + " " + id);
    }

    /** Same probe for a table whose id is database-generated ({@code creneau}). */
    public boolean exists(String table, long id) {
        return exists(table, ps -> ps.setLong(2, id), table + " " + id);
    }

    /** Deletes by id, {@code sql} naming the table and putting {@code edition_id = ?} first. */
    public void delete(String sql, String id) {
        delete(sql, ps -> ps.setString(2, id), id);
    }

    /** Same, for a database-generated id. */
    public void delete(String sql, long id) {
        delete(sql, ps -> ps.setLong(2, id), String.valueOf(id));
    }

    /** Binds whatever the probe or the delete needs beyond the edition. */
    @FunctionalInterface
    private interface Binding {
        void lier(PreparedStatement ps) throws SQLException;
    }

    private boolean exists(String table, Binding binding, String what) {
        return read("Failed to probe " + what, connection -> {
            try (PreparedStatement ps = prepareScoped(connection,
                    "SELECT 1 FROM " + table + " WHERE edition_id = ? AND id = ?")) {
                binding.lier(ps);
                // nosemgrep: java.lang.security.audit.formatted-sql-string.formatted-sql-string
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
    }

    private void delete(String sql, Binding binding, String what) {
        writeAndReturn("Failed to delete " + what, connection -> {
            try (PreparedStatement ps = prepareScoped(connection, sql)) {
                binding.lier(ps);
                return ps.executeUpdate();
            }
        });
    }
}
