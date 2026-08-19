package dev.sylvain.planning.api;

import dev.sylvain.planning.service.EditionContext;
import dev.sylvain.planning.service.EspaceAccesService;
import dev.sylvain.planning.service.ReferenceDataRepository;
import dev.sylvain.planning.service.ReferenceDataService;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

/**
 * The authentication guard of the espace animateur, bound declaratively to
 * every {@link SessionEspaceRequise} route: resolves the URL token, requires a
 * live {@code planning-espace} session of that animateur, and aborts with 404
 * (unknown token — nothing must help guessing one) or 401 (no session — the
 * interface then offers the code screen) before the resource method runs.
 *
 * <p>The resolved owner is handed to the resource through
 * {@link ProprietaireJetonCourant}, so the token is looked up once per
 * request. The session check itself runs inside the owner's edition, like
 * everything else in the espace.</p>
 */
@Provider
@SessionEspaceRequise
@Priority(Priorities.AUTHENTICATION)
public class SessionEspaceFilter implements ContainerRequestFilter {

    @Inject
    ReferenceDataService referenceDataService;

    @Inject
    EspaceAccesService espaceAccesService;

    @Inject
    EditionContext editionContext;

    @Inject
    ProprietaireJetonCourant proprietaireCourant;

    @Override
    public void filter(ContainerRequestContext contexte) {
        String jeton = contexte.getUriInfo().getPathParameters().getFirst("jeton");
        ReferenceDataRepository.ProprietaireJeton proprietaire =
                jeton == null ? null : referenceDataService.resoudreJetonAnimateur(jeton);
        if (proprietaire == null) {
            contexte.abortWith(Response.status(Response.Status.NOT_FOUND)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(new ReferenceDataResource.ErreurValidation("Lien inconnu ou expiré"))
                    .build());
            return;
        }
        Cookie cookie = contexte.getCookies().get(EspaceAnimateurResource.COOKIE_SESSION);
        boolean sessionValide = editionContext.executeDans(proprietaire.editionId(),
                () -> espaceAccesService.sessionValide(
                        cookie == null ? null : cookie.getValue(), proprietaire.animateurId()));
        if (!sessionValide) {
            contexte.abortWith(Response.status(Response.Status.UNAUTHORIZED)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(new ReferenceDataResource.ErreurValidation(
                            "Authentification requise : demandez un code d'accès par e-mail."))
                    .build());
            return;
        }
        proprietaireCourant.definir(proprietaire);
    }
}
