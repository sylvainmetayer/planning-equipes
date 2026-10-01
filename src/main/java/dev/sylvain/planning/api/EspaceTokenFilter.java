package dev.sylvain.planning.api;

import dev.sylvain.planning.service.EditionContext;
import dev.sylvain.planning.service.EditionRequestScope;
import dev.sylvain.planning.service.TokenOwner;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

/**
 * Token guard of the espace-animateur bootstrap routes ({@link TokenRequired}):
 * resolves the URL token, answers a plain 404 on an unknown one (nothing must
 * help guessing a token), and binds the owner — animateur <b>and</b> edition —
 * to the request through {@link EditionRequestScope}, so every service call
 * below runs in the owner's edition without any explicit wrapping.
 */
@Provider
@TokenRequired
@Priority(Priorities.AUTHENTICATION)
public class EspaceTokenFilter implements ContainerRequestFilter {

    /** The one answer to an unknown token and to one of an edition that is not active. */
    static final String UNKNOWN_LINK = "Lien inconnu, expiré, ou édition terminée";

    private final ReferenceDataService referenceDataService;

    private final EditionRequestScope editionRequestScope;

    private final EditionContext editionContext;

    @Inject
    public EspaceTokenFilter(
            ReferenceDataService referenceDataService,
            EditionRequestScope editionRequestScope,
            EditionContext editionContext) {
        this.editionContext = editionContext;
        this.referenceDataService = referenceDataService;
        this.editionRequestScope = editionRequestScope;
    }

    @Override
    public void filter(ContainerRequestContext contexte) {
        resoudreOuAborter(contexte);
    }

    /**
     * Shared with {@link SessionEspaceFilter}: {@code null} means the request was aborted with a 404.
     *
     * <p>A token of an edition that is not the active one is answered exactly
     * like an unknown token — same status, same body (ADR 0072): the espace of
     * an edition that is over, or not open yet, is closed, and the answer must
     * not tell a valid token from an invented one.</p>
     */
    TokenOwner resoudreOuAborter(ContainerRequestContext contexte) {
        String token = contexte.getUriInfo().getPathParameters().getFirst("jeton");
        TokenOwner owner = token == null ? null : referenceDataService.resolveAnimateurToken(token);
        if (owner == null || !editionContext.isActive(owner.editionId())) {
            contexte.abortWith(Response.status(Response.Status.NOT_FOUND)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(new ValidationError(UNKNOWN_LINK))
                    .build());
            return null;
        }
        editionRequestScope.setTokenOwner(owner);
        return owner;
    }
}
