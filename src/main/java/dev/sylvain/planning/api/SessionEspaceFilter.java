package dev.sylvain.planning.api;

import dev.sylvain.planning.service.EspaceAccesService;
import dev.sylvain.planning.service.ReferenceDataRepository;
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
 * every {@link SessionEspaceRequise} route: resolves the URL token (via
 * {@link JetonEspaceFilter}, which also binds the owner's edition to the
 * request), then requires a live {@code planning-espace} session of that
 * animateur. Aborts with 404 (unknown token) or 401 (no session — the
 * interface then offers the code screen) before the resource method runs.
 */
@Provider
@SessionEspaceRequise
@Priority(Priorities.AUTHENTICATION)
public class SessionEspaceFilter implements ContainerRequestFilter {

    @Inject
    JetonEspaceFilter jetonFilter;

    @Inject
    EspaceAccesService espaceAccesService;

    @Override
    public void filter(ContainerRequestContext contexte) {
        ReferenceDataRepository.ProprietaireJeton proprietaire = jetonFilter.resoudreOuAborter(contexte);
        if (proprietaire == null) {
            return;
        }
        // The owner's edition is bound to the request by now, so the session
        // lookup — like every call below — is already correctly scoped.
        Cookie cookie = contexte.getCookies().get(EspaceAnimateurResource.COOKIE_SESSION);
        if (!espaceAccesService.sessionValide(
                cookie == null ? null : cookie.getValue(), proprietaire.animateurId())) {
            contexte.abortWith(Response.status(Response.Status.UNAUTHORIZED)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(new ReferenceDataResource.ErreurValidation(
                            "Authentification requise : demandez un code d'accès par e-mail."))
                    .build());
        }
    }
}
