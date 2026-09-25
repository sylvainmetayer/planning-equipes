package dev.sylvain.planning.api;

import dev.sylvain.planning.service.BusinessError;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Turns a refusal from the domain into its HTTP answer, once, for every
 * endpoint — instead of the {@code try/catch} each resource method used to
 * write for itself.
 *
 * <p>The {@code switch} is exhaustive over the sealed {@link BusinessError}
 * hierarchy and carries no {@code default}: adding a kind of refusal is a
 * compile error here until someone decides what it answers.</p>
 *
 * <p>Only {@link BusinessError} lands here. A plain
 * {@code IllegalArgumentException} — one nobody meant to throw — stays
 * unhandled and keeps its 500 and its Sentry alert, which is the whole point
 * of having a type for "this refusal is deliberate".</p>
 */
@Provider
public class BusinessErrorMapper implements ExceptionMapper<BusinessError> {

    @Override
    public Response toResponse(BusinessError erreur) {
        Response.Status statut =
                switch (erreur) {
                    case BusinessError.Invalid _ -> Response.Status.BAD_REQUEST;
                    case BusinessError.NotFound _ -> Response.Status.NOT_FOUND;
                    case BusinessError.Conflict _ -> Response.Status.CONFLICT;
                    case BusinessError.Stale _ -> Response.Status.CONFLICT;
                };
        // A stale write is the one 409 the client answers with a dialog (reload
        // or overwrite), so its body says which 409 it is — the message alone
        // would be a sentence to read, not a case to switch on.
        Object corps = erreur instanceof BusinessError.Stale stale
                ? new StaleWriteError(stale.getMessage(), stale.getModifieLe())
                : new ValidationError(erreur.getMessage());
        return Response.status(statut)
                .entity(corps)
                .type(MediaType.APPLICATION_JSON)
                .build();
    }
}
