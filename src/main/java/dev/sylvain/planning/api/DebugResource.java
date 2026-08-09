package dev.sylvain.planning.api;

import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;

/**
 * Deliberately broken endpoint, wired to the "Exception back" button on the
 * Débogage tab: throwing here exercises {@code GlobalExceptionMapper}'s
 * report-to-Sentry/Bugsink path end-to-end, the same way the "Exception
 * front" button exercises the frontend's {@code ErrorHandler}. Not a
 * meaningful business operation, only a way to verify error tracking is
 * actually wired up in a given environment.
 */
@Path("/debug/test-exception")
public class DebugResource {

    @POST
    public void throwTestException() {
        throw new IllegalStateException("Test exception (bouton Débogage / Exception back)");
    }
}
