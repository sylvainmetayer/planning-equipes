package dev.sylvain.planning.service;

import jakarta.enterprise.context.RequestScoped;

/**
 * Carries the raw {@code X-Edition-Id} header of the request being served, set
 * once by {@code EditionHeaderFilter} and read by {@link EditionContext}.
 *
 * <p>Split out of {@link EditionContext} on purpose: the context itself must
 * be usable from threads with no active request (the solver runs its jobs on
 * a worker pool, exports run off a CLI), which a {@code @RequestScoped} bean
 * cannot be. Only the per-request slot lives here.</p>
 */
@RequestScoped
public class EditionRequestScope {

    private String editionIdDemande;

    public String getEditionIdDemande() {
        return editionIdDemande;
    }

    public void setEditionIdDemande(String editionIdDemande) {
        this.editionIdDemande = editionIdDemande;
    }
}
