package dev.sylvain.planning.api;

import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * Session endpoints (issue #165, ADR 0054). The login itself is Keycloak's
 * authorization code flow, entered through {@link #oidcLogin} — or, while the
 * break-glass door is open, Quarkus' form authentication (the Angular /login
 * page posts {@code j_username}/{@code j_password} to
 * {@code /j_security_check}). This resource only adds what neither mechanism
 * ships: a status probe for the frontend guard, and a logout.
 *
 * <p>Both status routes are deliberately reachable anonymously (see the
 * {@code auth-public} permission): the login page needs {@code /me} to answer
 * "not logged in" rather than 401, and an expired session must still be able
 * to log out cleanly.</p>
 */
@Path("/auth")
@Produces(MediaType.APPLICATION_JSON)
public class AuthResource {

    /** Must match {@code quarkus.http.auth.form.cookie-name}. */
    static final String COOKIE_SESSION = "planning-session";

    private final SecurityIdentity identity;

    @Inject
    public AuthResource(SecurityIdentity identity) {
        this.identity = identity;
    }

    /**
     * Who the caller is, under which name, and <b>with which roles</b>.
     *
     * <p>The roles are not decoration: "signed in" and "may administer" stopped
     * being the same sentence the moment an animateur could hold a session.
     * The interface has to know which shell to render, and the API answers 403
     * rather than 401 to the wrong one, which a frontend that only knew
     * {@code authentifie} would read as a transient failure.</p>
     */
    @GET
    @Path("/me")
    public StatutSession me() {
        boolean authentifie = !identity.isAnonymous();
        return new StatutSession(
                authentifie,
                authentifie ? identity.getPrincipal().getName() : null,
                authentifie ? List.copyOf(new TreeSet<>(identity.getRoles())) : List.of());
    }

    /**
     * Entry point of the Keycloak login: carries no logic of its own, and
     * that is the whole design. {@code @Authenticated} makes an anonymous call
     * fail the security check, Quarkus turns that into the OIDC challenge —
     * the 302 to the authorization endpoint, with PKCE and the state cookie —
     * and the browser comes back here once Keycloak is satisfied. By then the
     * identity exists and there is nothing left to do but send the visitor
     * where they were going.
     *
     * <p>Its own permission entry ({@code quarkus.http.auth.permission.oidc-login})
     * is {@code authenticated} rather than the {@code role-admin} that guards
     * the rest of {@code /api}: an animateur signs in through this same route,
     * and answering them 403 at the exact moment their credentials were
     * accepted would be an unusually cruel way to say "wrong door".</p>
     *
     * @param redirect where to land afterwards, restricted to a path of this
     *                 application. Anything else is ignored rather than
     *                 rejected: an open redirect on a route that runs right
     *                 after a successful login is precisely the one worth
     *                 handing to a phishing page, and there is no legitimate
     *                 caller to break by refusing.
     */
    @GET
    @Path("/oidc/login")
    @Authenticated
    public Response oidcLogin(@QueryParam("redirect") String redirect, @Context UriInfo uriInfo) {
        return Response.seeOther(target(uriInfo.getBaseUri(), redirect)).build();
    }

    /**
     * Turns the suggested path into the absolute URL to send the browser to.
     *
     * <p>The resolution is spelled out rather than left to
     * {@code Response.seeOther(URI.create("/"))}: a path-only URI there is
     * resolved by the JAX-RS runtime against the <b>base</b> URI of this
     * application, which is {@code /api}. Every successful Keycloak login
     * therefore landed on {@code http://host/api/} — a 404 page, at the exact
     * moment the visitor is supposed to arrive in the application. Resolving
     * against the base URI with {@link URI#resolve} instead is plain RFC 3986:
     * an absolute path replaces the whole path, and the host stays the one the
     * request came in on.</p>
     *
     * <p>The resolution is also where a path that is a legal <em>path</em> and
     * an illegal <em>URI</em> stops: {@link URI#resolve(String)} parses its
     * argument, so a space, a backslash or a stray {@code %} in the proposed
     * redirect throws {@link IllegalArgumentException} — a 500 at the exact
     * moment a login has just succeeded, from a query parameter anyone can
     * write. Unparseable is treated like every other redirect this application
     * declines to follow: the root.</p>
     */
    static URI target(URI base, String redirect) {
        URI racine = base.resolve("/");
        try {
            URI cible = base.resolve(localPath(redirect));
            // Belt and braces over localPath: whatever the parser made of the
            // suggestion, the browser only ever leaves for this scheme and
            // this host.
            return Objects.equals(cible.getScheme(), base.getScheme())
                            && Objects.equals(cible.getRawAuthority(), base.getRawAuthority())
                    ? cible
                    : racine;
        } catch (IllegalArgumentException malforme) {
            return racine;
        }
    }

    /**
     * Drops the break-glass session cookie. Idempotent: logging out twice is
     * fine.
     *
     * <p>For a Keycloak session this is <b>not</b> where a logout ends: Quarkus
     * owns the OIDC cookie. The answer therefore names the route that ends the
     * Keycloak session too ({@code quarkus.oidc.logout.path}), which the
     * frontend navigates to — dropping a local cookie alone would leave the
     * identity provider ready to sign the visitor straight back in.</p>
     */
    @POST
    @Path("/logout")
    @APIResponse(
            responseCode = "200",
            // Declared by hand because the method returns a Response (it has a
            // cookie to expire), which the schema generator cannot look
            // inside. Without it the payload would cross the wire undescribed,
            // and `npm run api-types-check` would have no contract to hold the
            // frontend's model against.
            content = @Content(schema = @Schema(implementation = Deconnexion.class)))
    public Response logout() {
        NewCookie expiration = new NewCookie.Builder(COOKIE_SESSION)
                .value("")
                .path("/")
                .maxAge(0)
                .httpOnly(true)
                .build();
        boolean sessionKeycloak = !identity.isAnonymous() && identity.getPrincipal() instanceof JsonWebToken;
        return Response.ok(new Deconnexion(sessionKeycloak ? "/api/auth/oidc/logout" : null))
                .cookie(expiration)
                .build();
    }

    /**
     * Characters a path of this application may carry, query and fragment
     * included. No backslash, no control character, no space: each is a way
     * some browser rewrites a "path" into another host.
     */
    private static final Pattern CHEMIN_LOCAL = Pattern.compile("/[A-Za-z0-9\\-._~%/?=&#+@!$'()*,;:]*");

    /** A path of this application, or the root when the caller proposed anything else. */
    static String localPath(String redirect) {
        if (redirect == null || redirect.isBlank()) {
            return "/";
        }
        // "//host" is a path to a browser's eye and an absolute URL to its
        // resolver: the leading slash is not enough on its own.
        if (redirect.startsWith("//") || !CHEMIN_LOCAL.matcher(redirect).matches()) {
            return "/";
        }
        return redirect;
    }

    /**
     * @param authentifie whether a session is open at all
     * @param nom         the principal's name — the address of the Keycloak
     *                    account, or {@code admin} for the break-glass account
     * @param roles       sorted, so the payload does not churn between calls
     */
    @Schema(requiredProperties = {"authentifie", "roles"})
    public record StatutSession(boolean authentifie, String nom, List<String> roles) {}

    /**
     * @param urlDeconnexion route that ends the identity provider's session,
     *                       {@code null} when there is none to end
     */
    public record Deconnexion(String urlDeconnexion) {}
}
