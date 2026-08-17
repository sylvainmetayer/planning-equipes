package dev.sylvain.planning.api;

import dev.sylvain.planning.service.EditionContext;
import dev.sylvain.planning.service.EditionRequestScope;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;

/**
 * Reads the {@code X-Edition-Id} header into the request scope, so every
 * reference-data query served by this request lands in the edition the client
 * designated (see {@link EditionContext}).
 *
 * <p>Nothing is validated here: an unknown id is resolved to the default
 * edition downstream rather than rejected, so a tab left open on a
 * since-deleted edition keeps working instead of 400-ing on every screen.</p>
 */
@Provider
public class EditionHeaderFilter implements ContainerRequestFilter {

    @Inject
    EditionRequestScope requestScope;

    @Override
    public void filter(ContainerRequestContext requestContext) {
        requestScope.setEditionIdDemande(requestContext.getHeaderString(EditionContext.HEADER));
    }
}
