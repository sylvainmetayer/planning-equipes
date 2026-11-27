package dev.sylvain.planning.observability;

import java.sql.SQLException;

/**
 * PostgreSQL refuses U+0000 in any text: a request that carried one, in a
 * path, a query, a body or an MCP argument, is the caller's error wherever its
 * SQL lives. Recognised here once, for the two transports that answer it —
 * {@link GlobalExceptionMapper} with a 400, the MCP refusal interceptor with a
 * tool result in error — so neither turns it into « Internal error ».
 */
public final class NulCharacterRefusal {

    /** What both transports answer. */
    public static final String MESSAGE = "Le caractère nul (U+0000) n'est accepté dans aucun texte.";

    private static final String SQLSTATE = "22021";

    private NulCharacterRefusal() {}

    /** Whether the database refused this failure for a U+0000 it was sent. */
    public static boolean causedBy(Throwable exception) {
        return SQLSTATE.equals(sqlState(exception));
    }

    /** The SQLSTATE of the first SQL exception in the cause chain, {@code null} without one. */
    static String sqlState(Throwable exception) {
        for (Throwable t = exception; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return null;
    }
}
