package dev.sylvain.planning.api;

import dev.sylvain.planning.service.EditionRequestScope;
import dev.sylvain.planning.service.ReferenceDataService;
import dev.sylvain.planning.service.TokenOwner;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

/**
 * Guard of the calendar subscription route ({@link AbonnementTokenRequired}).
 * Same shape as {@link EspaceTokenFilter}, and a strictly separate token: it
 * reads {@code abonnement_token}, so an espace access token pasted here
 * resolves to nobody, and the other way round.
 *
 * <p>An unknown token gets a 404 carrying nothing but one fixed sentence — a
 * calendar client shows whatever it is handed, so the body says the address is
 * dead without naming the token or telling a revoked one from an invented
 * one.</p>
 */
@Provider
@AbonnementTokenRequired
@Priority(Priorities.AUTHENTICATION)
public class AbonnementTokenFilter implements ContainerRequestFilter {

    @Inject
    ReferenceDataService referenceDataService;

    @Inject
    EditionRequestScope editionRequestScope;

    @Override
    public void filter(ContainerRequestContext contexte) {
        String token = contexte.getUriInfo().getPathParameters().getFirst("token");
        TokenOwner owner =
                token == null ? null : referenceDataService.resolveAbonnementToken(token);
        if (owner == null) {
            contexte.abortWith(Response.status(Response.Status.NOT_FOUND)
                    .type(MediaType.TEXT_PLAIN)
                    .entity("Abonnement inconnu ou révoqué")
                    .build());
            return;
        }
        editionRequestScope.setTokenOwner(owner);
    }
}
