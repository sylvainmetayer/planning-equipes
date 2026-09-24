package dev.sylvain.planning.api;

import dev.sylvain.planning.service.EditionRequestScope;
import dev.sylvain.planning.service.TokenOwner;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Token half of the espace-animateur guard, called by {@link SessionEspaceFilter}:
 * resolves the URL token, answers a plain 404 on an unknown one (nothing must
 * help guessing a token), and binds the owner — animateur <b>and</b> edition —
 * to the request through {@link EditionRequestScope}, so every service call
 * below runs in the owner's edition without any explicit wrapping.
 *
 * <p>It used to be a filter of its own, for the two bootstrap routes of the
 * e-mail code (asking for a code, exchanging it for a session) that could not
 * demand the session they existed to create. Keycloak owns that step now, so
 * every espace route wants the session and the token alone opens nothing.</p>
 */
@ApplicationScoped
public class EspaceTokenFilter {

    private final ReferenceDataService referenceDataService;

    private final EditionRequestScope editionRequestScope;

    @Inject
    public EspaceTokenFilter(ReferenceDataService referenceDataService, EditionRequestScope editionRequestScope) {
        this.referenceDataService = referenceDataService;
        this.editionRequestScope = editionRequestScope;
    }

    /** Shared with {@link SessionEspaceFilter}: {@code null} means the request was aborted with a 404. */
    TokenOwner resoudreOuAborter(ContainerRequestContext contexte) {
        String token = contexte.getUriInfo().getPathParameters().getFirst("jeton");
        TokenOwner owner = token == null ? null : referenceDataService.resolveAnimateurToken(token);
        if (owner == null) {
            contexte.abortWith(Response.status(Response.Status.NOT_FOUND)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(new ValidationError("Lien inconnu ou expiré"))
                    .build());
            return null;
        }
        editionRequestScope.setTokenOwner(owner);
        return owner;
    }
}
