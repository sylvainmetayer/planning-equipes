package dev.sylvain.planning.api;

import dev.sylvain.planning.service.EditionRequestScope;
import dev.sylvain.planning.service.ProprietaireJeton;
import dev.sylvain.planning.service.ReferenceDataService;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

/**
 * Token guard of the espace-animateur bootstrap routes ({@link JetonRequis}):
 * resolves the URL token, answers a plain 404 on an unknown one (nothing must
 * help guessing a token), and binds the owner — animateur <b>and</b> edition —
 * to the request through {@link EditionRequestScope}, so every service call
 * below runs in the owner's edition without any explicit wrapping.
 */
@Provider
@JetonRequis
@Priority(Priorities.AUTHENTICATION)
public class JetonEspaceFilter implements ContainerRequestFilter {

    @Inject
    ReferenceDataService referenceDataService;

    @Inject
    EditionRequestScope editionRequestScope;

    @Override
    public void filter(ContainerRequestContext contexte) {
        resoudreOuAborter(contexte);
    }

    /** Shared with {@link SessionEspaceFilter}: {@code null} means the request was aborted with a 404. */
    ProprietaireJeton resoudreOuAborter(ContainerRequestContext contexte) {
        String jeton = contexte.getUriInfo().getPathParameters().getFirst("jeton");
        ProprietaireJeton proprietaire =
                jeton == null ? null : referenceDataService.resoudreJetonAnimateur(jeton);
        if (proprietaire == null) {
            contexte.abortWith(Response.status(Response.Status.NOT_FOUND)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(new ErreurValidation("Lien inconnu ou expiré"))
                    .build());
            return null;
        }
        editionRequestScope.setProprietaireJeton(proprietaire);
        return proprietaire;
    }
}
