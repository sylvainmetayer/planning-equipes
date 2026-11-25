package dev.sylvain.planning.api;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;

/**
 * What a responsable de stand reads (shifts, perhaps names, the printable sheet) is never
 * kept by a browser or a proxy: the rights and the setting behind it change, and a shared
 * workstation must not hand the next person the last one's screen.
 */
@Provider
public class ResponsableNoStoreFilter implements ContainerResponseFilter {

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        if (request.getUriInfo().getPath().replaceFirst("^/", "").startsWith("responsable/")) {
            response.getHeaders().putSingle("Cache-Control", "no-store");
        }
    }
}
