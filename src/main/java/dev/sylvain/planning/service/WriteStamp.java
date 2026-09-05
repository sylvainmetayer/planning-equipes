package dev.sylvain.planning.service;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;

/**
 * Reads the {@code modifie_le} a referential write hands back through
 * {@code RETURNING modifie_le} (issue #362), so the entity the caller gets
 * carries the stamp the database actually wrote — the one the next write of
 * that client will be checked against — instead of {@code null} or the stamp
 * it was sent with.
 */
final class WriteStamp {

    private WriteStamp() {
    }

    /** Executes the write and returns the single {@code modifie_le} it returned. */
    static Instant written(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                throw new SQLException("The write returned no row");
            }
            return rs.getObject("modifie_le", OffsetDateTime.class).toInstant();
        }
    }
}
