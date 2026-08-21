package dev.sylvain.planning.api;

import dev.sylvain.planning.service.ErreurMetier;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Turns a refusal from the domain into its HTTP answer, once, for every
 * endpoint — instead of the {@code try/catch} each resource method used to
 * write for itself.
 *
 * <p>The {@code switch} is exhaustive over the sealed {@link ErreurMetier}
 * hierarchy and carries no {@code default}: adding a kind of refusal is a
 * compile error here until someone decides what it answers.</p>
 *
 * <p>Only {@link ErreurMetier} lands here. A plain
 * {@code IllegalArgumentException} — one nobody meant to throw — stays
 * unhandled and keeps its 500 and its Sentry alert, which is the whole point
 * of having a type for "this refusal is deliberate".</p>
 */
@Provider
public class ErreurMetierMapper implements ExceptionMapper<ErreurMetier> {

    @Override
    public Response toResponse(ErreurMetier erreur) {
        Response.Status statut = switch (erreur) {
            case ErreurMetier.Invalide ignored -> Response.Status.BAD_REQUEST;
            case ErreurMetier.Introuvable ignored -> Response.Status.NOT_FOUND;
            case ErreurMetier.Conflit ignored -> Response.Status.CONFLICT;
        };
        return Response.status(statut)
                .entity(new ErreurValidation(erreur.getMessage()))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }
}
