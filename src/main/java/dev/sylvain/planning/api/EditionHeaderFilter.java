package dev.sylvain.planning.api;

import dev.sylvain.planning.service.EditionContext;
import dev.sylvain.planning.service.EditionRequestScope;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.ext.Provider;

/**
 * Reads the {@code X-Edition-Id} header into the request scope, so every
 * reference-data query served by this request lands in the edition the client
 * designated (see {@link EditionContext}).
 *
 * <p>Nothing is validated here: an unknown id is resolved to the default
 * edition downstream rather than rejected, so a tab left open on a
 * since-deleted edition keeps working instead of 400-ing on every screen.</p>
 *
 * <p><b>{@code @PreMatching}, so it runs before every other request filter</b>
 * — and deliberately without a {@code @Priority}, which would be misleading
 * here: priorities only order filters against each other <em>within</em> the
 * pre-matching phase, and this is the only one. A
 * {@code @Priority(HEADER_DECORATOR)} would read as "runs early" while being
 * 3000, i.e. after {@code AUTHENTICATION} (1000) — so if {@code @PreMatching}
 * ever went away the filter would fall back exactly into the order this note
 * calls wrong, while looking fixed.</p>
 *
 * <p>It used to carry no annotation at all, which put it at the default
 * {@code USER} (5000), after the espace guards. Nothing broke, because an
 * espace token overrides the header anyway and {@link EditionContext} reads it
 * first; but the ordering held by luck, and the next filter added below 5000
 * that asked for the current edition would silently have got the default
 * one.</p>
 */
@Provider
@PreMatching
public class EditionHeaderFilter implements ContainerRequestFilter {

    @Inject
    EditionRequestScope requestScope;

    @Override
    public void filter(ContainerRequestContext requestContext) {
        requestScope.setEditionIdDemande(requestContext.getHeaderString(EditionContext.HEADER));
    }
}
