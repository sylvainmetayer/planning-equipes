package dev.sylvain.planning.service;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;

/**
 * Binds the precondition of a referential write and reads back the
 * {@code modifie_le} it hands through
 * {@code RETURNING modifie_le} (issue #362), so the entity the caller gets
 * carries the stamp the database actually wrote — the one the next write of
 * that client will be checked against — instead of {@code null} or the stamp
 * it was sent with.
 */
public final class WriteStamp {

    private WriteStamp() {}

    /** Executes the write and returns the single {@code modifie_le} it returned. */
    public static Instant written(PreparedStatement ps) throws SQLException {
        Instant written = writtenOrRefused(ps);
        if (written == null) {
            throw new SQLException("The write returned no row");
        }
        return written;
    }

    /**
     * Same, but {@code null} when the statement's own precondition rejected the
     * row: the write and its check are one statement (issue #362), so "no row
     * came back" is the refusal, not an error.
     */
    public static Instant writtenOrRefused(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                return null;
            }
            return rs.getObject("modifie_le", OffsetDateTime.class).toInstant();
        }
    }

    /**
     * Binds the three placeholders every referential write ends with, starting
     * at {@code index}: may this write land on a row that already exists, and
     * — when set — the {@code modifie_le} the caller read before editing. The
     * clause itself is written out in each statement, like every other SQL
     * here, and reads the <b>stored</b> row, never the proposed one. Compared
     * at the millisecond: the database keeps microseconds, a client that went
     * through a {@code Date} does not, and two writes of the same row in the
     * same millisecond are not a case worth a false conflict.
     */
    public static void bindPrecondition(PreparedStatement ps, int index, boolean updateAllowed, Instant expected)
            throws SQLException {
        ps.setBoolean(index, updateAllowed);
        Timestamp attendu = expected == null ? null : Timestamp.from(expected);
        ps.setTimestamp(index + 1, attendu);
        ps.setTimestamp(index + 2, attendu);
    }
}
