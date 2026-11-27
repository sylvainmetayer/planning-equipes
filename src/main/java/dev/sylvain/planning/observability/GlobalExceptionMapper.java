package dev.sylvain.planning.observability;

import io.sentry.Sentry;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.io.IOException;
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

    @Override
    public Response toResponse(Throwable exception) {
        if (exception instanceof WebApplicationException webException) {
            return webException.getResponse();
        }
        if (NulCharacterRefusal.causedBy(exception)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorBody(NulCharacterRefusal.MESSAGE))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }
        if (clientClosedConnection(exception)) {
            // Nobody is left to read the answer, and nothing is broken: not an alert.
            LOG.debug("Client closed the connection before the response was written", exception);
        } else {
            LOG.error("Unhandled exception", exception);
            Sentry.captureException(exception);
        }
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(new ErrorBody("Internal server error"))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }

    /**
     * Whether writing the response failed because the client went away — a
     * download cancelled, a tab closed. The Vert.x output stream says so in
     * these words, around the channel's own exception; a closed channel alone
     * may be a server-side one, and stays an alert.
     *
     * <p>shortcut: matched on Quarkus's wording; should it change, these go
     * back to ERROR and Sentry — loud, never silent.</p>
     */
    static boolean clientClosedConnection(Throwable exception) {
        for (Throwable t = exception; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof IOException
                    && ("Failed to write".equals(t.getMessage())
                            || "Connection has been closed".equals(t.getMessage()))) {
                return true;
            }
        }
        return false;
    }

    private record ErrorBody(String message) {}
}
