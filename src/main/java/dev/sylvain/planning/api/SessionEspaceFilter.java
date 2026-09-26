package dev.sylvain.planning.api;

import dev.sylvain.planning.service.EditionRequestScope;
import dev.sylvain.planning.service.TokenOwner;
import dev.sylvain.planning.service.espace.OidcAuthentication;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

/**
 * The authentication guard of the espace animateur, bound declaratively to
 * every {@link EspaceSessionRequired} route: resolves the URL token (via
 * {@link EspaceTokenFilter}, which also binds the owner's edition to the
 * request), then requires a Keycloak session asserting the very address that
 * fiche carries. Aborts with 404 (unknown token) or 401 (no such session — the
 * interface then offers the sign-in button) before the resource method runs.
 * Once that session holds, it marks the identity as proven on
 * {@link EditionRequestScope}: that, and not the token, is what lets the
 * history name the animateur as the author of what follows.
 *
 * <p>Neither half is enough alone, and that is the whole design (ADR 0054).
 * One Keycloak account is one <b>person</b>, who may hold a fiche in several
 * editions: the account says who is knocking, the token says which espace
 * opens. An animateur who picks up a colleague's link is authenticated — and it
 * is someone else's fiche. The link alone opens nothing either: the espace
 * serves the planning for download.</p>
 *
 * <p>With Keycloak off — the break-glass mode — the espace is closed: the
 * six-digit code that used to open it is gone, and the password form is an
 * administrator's door, not an animateur's.</p>
 */
@Provider
@EspaceSessionRequired
@Priority(Priorities.AUTHENTICATION)
public class SessionEspaceFilter implements ContainerRequestFilter {

    private final EspaceTokenFilter tokenFilter;

    private final OidcAuthentication oidc;

    private final EditionRequestScope editionRequestScope;

    @Inject
    public SessionEspaceFilter(
            EspaceTokenFilter tokenFilter, OidcAuthentication oidc, EditionRequestScope editionRequestScope) {
        this.tokenFilter = tokenFilter;
        this.oidc = oidc;
        this.editionRequestScope = editionRequestScope;
    }

    @Override
    public void filter(ContainerRequestContext contexte) {
        TokenOwner owner = tokenFilter.resoudreOuAborter(contexte);
        if (owner == null) {
            return;
        }
        // The owner's edition is bound to the request by now, so every call
        // below is already correctly scoped.
        if (keycloakAtteste(owner)) {
            editionRequestScope.markIdentityProven();
        } else {
            contexte.abortWith(Response.status(Response.Status.UNAUTHORIZED)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(new ValidationError("Authentification requise : connectez-vous avec votre compte."))
                    .build());
        }
    }

    /**
     * True when the Keycloak session asserts the address carried by the fiche
     * this token belongs to. A fiche without an e-mail never matches: the
     * address is the identity.
     */
    private boolean keycloakAtteste(TokenOwner owner) {
        if (owner.email() == null || owner.email().isBlank()) {
            return false;
        }
        return oidc.trustedEmail()
                .filter(email -> email.equals(OidcAuthentication.normalize(owner.email())))
                .isPresent();
    }
}
