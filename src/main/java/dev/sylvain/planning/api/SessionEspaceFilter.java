package dev.sylvain.planning.api;

import dev.sylvain.planning.service.EditionRequestScope;
import dev.sylvain.planning.service.TokenOwner;
import dev.sylvain.planning.service.espace.EspaceAccesService;
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
 * every {@link EspaceSessionRequired} route: resolves the URL token (via
 * {@link EspaceTokenFilter}, which also binds the owner's edition to the
 * request), then requires a live {@code planning-espace} session of that
 * animateur. Aborts with 404 (unknown token) or 401 (no session — the
 * interface then offers the code screen) before the resource method runs.
 * Once the session holds — nothing else proves it, no request header among them — it marks the identity as
 * proven on {@link EditionRequestScope}: that, and not the token, is what lets
 * the history name the animateur as the author of what follows.
 */
@Provider
@EspaceSessionRequired
@Priority(Priorities.AUTHENTICATION)
public class SessionEspaceFilter implements ContainerRequestFilter {

    private final EspaceTokenFilter tokenFilter;

    private final EspaceAccesService espaceAccesService;

    private final EditionRequestScope editionRequestScope;

    @Inject
    public SessionEspaceFilter(
            EspaceTokenFilter tokenFilter,
            EspaceAccesService espaceAccesService,
            EditionRequestScope editionRequestScope) {
        this.tokenFilter = tokenFilter;
        this.espaceAccesService = espaceAccesService;
        this.editionRequestScope = editionRequestScope;
    }

    @Override
    public void filter(ContainerRequestContext contexte) {
        TokenOwner owner = tokenFilter.resoudreOuAborter(contexte);
        if (owner == null) {
            return;
        }
        // The owner's edition is bound to the request by now, so the session
        // lookup — like every call below — is already correctly scoped.
        Cookie cookie = contexte.getCookies().get(EspaceAnimateurResource.COOKIE_SESSION);
        if (espaceAccesService.validSession(cookie == null ? null : cookie.getValue(), owner.animateurId())) {
            editionRequestScope.markIdentityProven();
        } else {
            contexte.abortWith(Response.status(Response.Status.UNAUTHORIZED)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(new ValidationError("Authentification requise : demandez un code d'accès par e-mail."))
                    .build());
        }
    }
}
