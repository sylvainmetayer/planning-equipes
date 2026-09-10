package dev.sylvain.planning.api;

import dev.sylvain.planning.service.espace.DemandeEchangeService;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

/**
 * Guard of the {@link FoireOpenRequired} routes, in the same shape as
 * {@link EspaceTokenFilter} and {@link SessionEspaceFilter}: the requirement is
 * declared on the route, and a reader sees the three guards of a route in one
 * glance rather than hunting for a check in a method body.
 *
 * <p>Runs at {@link Priorities#AUTHORIZATION}, so <b>after</b> the
 * authentication-priority filters: an anonymous caller gets its 401 without
 * ever learning whether the foire is open.</p>
 */
@Provider
@FoireOpenRequired
@Priority(Priorities.AUTHORIZATION)
public class FoireOpenFilter implements ContainerRequestFilter {

    @Inject
    DemandeEchangeService demandeEchangeService;

    @Override
    public void filter(ContainerRequestContext contexte) {
        if (demandeEchangeService.isFoireOpen()) {
            return;
        }
        contexte.abortWith(Response.status(Response.Status.BAD_REQUEST)
                .type(MediaType.APPLICATION_JSON)
                .entity(new ValidationError(DemandeEchangeService.FOIRE_FERMEE))
                .build());
    }
}
