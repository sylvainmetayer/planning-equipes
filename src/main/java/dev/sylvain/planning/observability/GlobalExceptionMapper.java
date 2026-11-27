package dev.sylvain.planning.observability;

import io.sentry.Sentry;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.sql.SQLException;
import org.jboss.logging.Logger;

/**
 * Reports every unhandled REST exception to Sentry/Bugsink (a no-op if
 * {@link SentryInitializer} never configured a DSN) before answering. Resource
 * methods already return deliberate {@code 400}/{@code 404}/{@code 409}
 * responses themselves (see e.g. {@code ReferenceDataResource},
 * {@code SolverJobResource}) or throw a JAX-RS {@link WebApplicationException}
 * for expected conditions (e.g. {@code NotFoundException}); those carry their
 * own status and are not worth alerting on, so only genuinely unexpected
 * exceptions are captured here.
 */
@Provider
public class GlobalExceptionMapper implements ExceptionMapper<Throwable> {

    private static final Logger LOG = Logger.getLogger(GlobalExceptionMapper.class);

    /**
     * PostgreSQL refuses U+0000 in any text: a request that carried one, in a
     * path, a query or a body, is the client's error wherever its SQL lives.
     */
    private static final String NUL_CHARACTER = "22021";

    @Override
    public Response toResponse(Throwable exception) {
        if (exception instanceof WebApplicationException webException) {
            return webException.getResponse();
        }
        if (NUL_CHARACTER.equals(sqlState(exception))) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorBody("Le caractère nul (U+0000) n'est accepté dans aucun texte."))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }
        LOG.error("Unhandled exception", exception);
        Sentry.captureException(exception);
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(new ErrorBody("Internal server error"))
                .type(MediaType.APPLICATION_JSON)
                .build();
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

    private record ErrorBody(String message) {}
}
