package dev.sylvain.planning.api;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

/**
 * Refuses an {@link InstanceOnly} route to a signed-in caller who is not an
 * administrator (403).
 *
 * <p>An anonymous caller passes: the HTTP permission in front of {@code /api}
 * has already decided about them (it is open under {@code %test}, and the
 * public routes are public on purpose), and this filter only narrows what a
 * signed-in organiser may do. The MCP key never reaches {@code /api}.</p>
 */
@Provider
@Priority(Priorities.AUTHORIZATION)
public class InstanceOnlyFilter implements ContainerRequestFilter {

    @Context
    ResourceInfo resource;

    private final SecurityIdentity identity;

    @Inject
    public InstanceOnlyFilter(SecurityIdentity identity) {
        this.identity = identity;
    }

    @Override
    public void filter(ContainerRequestContext requestContext) {
        if (identity.isAnonymous() || identity.hasRole("admin") || resource.getResourceMethod() == null) {
            return;
        }
        if (resource.getResourceMethod().isAnnotationPresent(InstanceOnly.class)
                || resource.getResourceClass().isAnnotationPresent(InstanceOnly.class)) {
            requestContext.abortWith(Response.status(Response.Status.FORBIDDEN).build());
        }
    }
}
