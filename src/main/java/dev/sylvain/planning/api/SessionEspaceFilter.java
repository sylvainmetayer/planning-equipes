package dev.sylvain.planning.api;

import java.util.Locale;

import dev.sylvain.planning.service.espace.EspaceAccesService;
import dev.sylvain.planning.service.TokenOwner;
import dev.sylvain.planning.service.espace.RemoteUserAuthentication;
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
 *
 * <p>When the remote-user mode is enabled, an access proxy asserting the
 * token owner's own address takes the place of that session. The e-mail is
 * precisely what the code screen proves — it sends a six-digit code to the
 * address on the fiche — so an assertion from the proxy that already
 * authenticated the person is the same fact established one step earlier, not
 * a weaker one. The link alone still is not enough: the address has to match
 * the fiche the token designates, so a proxy-authenticated animateur cannot
 * open a colleague's espace by picking up their link.</p>
 */
@Provider
@EspaceSessionRequired
@Priority(Priorities.AUTHENTICATION)
public class SessionEspaceFilter implements ContainerRequestFilter {

    @Inject
    EspaceTokenFilter tokenFilter;

    @Inject
    EspaceAccesService espaceAccesService;

    @Inject
    RemoteUserAuthentication remoteUser;

    @Override
    public void filter(ContainerRequestContext contexte) {
        TokenOwner owner = tokenFilter.resoudreOuAborter(contexte);
        if (owner == null) {
            return;
        }
        // The owner's edition is bound to the request by now, so the session
        // lookup — like every call below — is already correctly scoped.
        if (proxyAtteste(contexte, owner)) {
            return;
        }
        Cookie cookie = contexte.getCookies().get(EspaceAnimateurResource.COOKIE_SESSION);
        if (!espaceAccesService.validSession(
                cookie == null ? null : cookie.getValue(), owner.animateurId())) {
            contexte.abortWith(Response.status(Response.Status.UNAUTHORIZED)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(new ValidationError(
                            "Authentification requise : demandez un code d'accès par e-mail."))
                    .build());
        }
    }

    /**
     * True when the proxy asserts the address carried by the very fiche this
     * token belongs to. A fiche without an e-mail can never match: it is
     * exactly the fiche the code screen already refuses to serve, since the
     * address is the second factor.
     */
    private boolean proxyAtteste(ContainerRequestContext contexte,
            TokenOwner owner) {
        if (owner.email() == null || owner.email().isBlank()) {
            return false;
        }
        return remoteUser.trustedEmail(nom -> contexte.getHeaderString(nom))
                .filter(email -> email.equals(owner.email().trim().toLowerCase(Locale.ROOT)))
                .isPresent();
    }
}
