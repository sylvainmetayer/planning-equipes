package dev.sylvain.planning.api;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;

/**
 * Mirrors an incoming {@code X-Pangolin} header onto the response.
 *
 * <p>The MCP page (see {@code mcp-page.ts}) detects an access proxy by
 * reading {@code X-Pangolin: true} off an API <em>response</em>. Pangolin
 * itself has no way to inject that: its "custom headers" target setting only
 * ever reaches the <em>request</em> this application receives, not the
 * response sent back to the browser. Without this filter, an operator can
 * configure that setting exactly as documented and the detection still never
 * fires — this closes that gap by echoing whatever value Pangolin put on the
 * request straight back onto the response.</p>
 */
@Provider
public class PangolinHeaderFilter implements ContainerResponseFilter {

    static final String EN_TETE = "X-Pangolin";

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
        String valeur = requestContext.getHeaderString(EN_TETE);
        if (valeur != null) {
            responseContext.getHeaders().putSingle(EN_TETE, valeur);
        }
    }
}
