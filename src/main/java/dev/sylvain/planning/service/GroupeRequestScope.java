package dev.sylvain.planning.service;

import jakarta.enterprise.context.RequestScoped;

/**
 * Carries the raw {@code X-Groupe-Id} header of the request being served, set
 * once by {@code GroupeHeaderFilter} and read by {@link GroupeContext}.
 *
 * <p>Split out of {@link GroupeContext} on purpose: the context itself must be
 * usable from threads with no active request (the solver runs its jobs on a
 * worker pool, exports run off a CLI), which a {@code @RequestScoped} bean
 * cannot be. Only the per-request slot lives here.</p>
 */
@RequestScoped
public class GroupeRequestScope {

    private String groupeIdDemande;

    public String getGroupeIdDemande() {
        return groupeIdDemande;
    }

    public void setGroupeIdDemande(String groupeIdDemande) {
        this.groupeIdDemande = groupeIdDemande;
    }
}
