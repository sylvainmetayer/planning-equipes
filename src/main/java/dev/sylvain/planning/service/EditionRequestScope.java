package dev.sylvain.planning.service;

import jakarta.enterprise.context.RequestScoped;

/**
 * Carries the per-request inputs of the edition resolution: the raw
 * {@code X-Edition-Id} header (set once by {@code EditionHeaderFilter}) and,
 * on the espace-animateur routes, the owner the URL token resolved to (set by
 * the espace guards) — whose edition then <b>overrides</b> the header, because
 * the espace never trusts what the browser claims. Both are read by
 * {@link EditionContext}.
 *
 * <p>Split out of {@link EditionContext} on purpose: the context itself must
 * be usable from threads with no active request (the solver runs its jobs on
 * a worker pool, exports run off a CLI), which a {@code @RequestScoped} bean
 * cannot be. Only the per-request slots live here.</p>
 */
@RequestScoped
public class EditionRequestScope {

    private String editionIdDemande;
    private TokenOwner tokenOwner;

    public String getEditionIdDemande() {
        return editionIdDemande;
    }

    public void setEditionIdDemande(String editionIdDemande) {
        this.editionIdDemande = editionIdDemande;
    }

    /** The (édition, animateur) of the request's espace token, {@code null} off the espace routes. */
    public TokenOwner getTokenOwner() {
        return tokenOwner;
    }

    public void setTokenOwner(TokenOwner tokenOwner) {
        this.tokenOwner = tokenOwner;
    }
}
