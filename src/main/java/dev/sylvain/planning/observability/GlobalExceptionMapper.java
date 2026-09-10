package dev.sylvain.planning.observability;

import io.sentry.Sentry;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
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
        LOG.error("Unhandled exception", exception);
        Sentry.captureException(exception);
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(new ErrorBody("Internal server error"))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }

    private record ErrorBody(String message) {}
}
