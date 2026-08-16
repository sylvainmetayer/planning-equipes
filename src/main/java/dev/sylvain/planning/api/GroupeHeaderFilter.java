package dev.sylvain.planning.api;

import dev.sylvain.planning.service.GroupeContext;
import dev.sylvain.planning.service.GroupeRequestScope;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;

/**
 * Reads the {@code X-Groupe-Id} header into the request scope, so every
 * reference-data query served by this request lands in the group the client
 * designated (see {@link GroupeContext}).
 *
 * <p>Nothing is validated here: an unknown id is resolved to the default group
 * downstream rather than rejected, so a tab left open on a since-deleted group
 * keeps working instead of 400-ing on every screen.</p>
 */
@Provider
public class GroupeHeaderFilter implements ContainerRequestFilter {

    @Inject
    GroupeRequestScope requestScope;

    @Override
    public void filter(ContainerRequestContext requestContext) {
        requestScope.setGroupeIdDemande(requestContext.getHeaderString(GroupeContext.HEADER));
    }
}
