package dev.sylvain.planning.api;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;

/**
 * Session endpoints of the admin form login (issue #165). The login itself is
 * Quarkus' form authentication: the Angular /login page posts
 * {@code j_username}/{@code j_password} to {@code /j_security_check} and gets
 * 200 + the encrypted session cookie, or 401. This resource only adds what
 * that mechanism does not ship: a status probe for the frontend guard, and a
 * logout that drops the cookie.
 *
 * <p>Both routes are deliberately reachable anonymously (see the
 * {@code auth-public} permission): the login page needs {@code /me} to answer
 * "not logged in" rather than 401, and an expired session must still be able
 * to log out cleanly.</p>
 */
@Path("/auth")
@Produces(MediaType.APPLICATION_JSON)
public class AuthResource {

    /** Must match {@code quarkus.http.auth.form.cookie-name}. */
    static final String COOKIE_SESSION = "planning-session";

    @Inject
    SecurityIdentity identity;

    /** Whether the caller holds a valid admin session, and under which name. */
    @GET
    @Path("/me")
    public StatutSession me() {
        boolean authentifie = !identity.isAnonymous();
        return new StatutSession(authentifie, authentifie ? identity.getPrincipal().getName() : null);
    }

    /** Drops the session cookie. Idempotent: logging out twice is fine. */
    @POST
    @Path("/logout")
    public Response logout() {
        NewCookie expiration = new NewCookie.Builder(COOKIE_SESSION)
                .value("")
                .path("/")
                .maxAge(0)
                .httpOnly(true)
                .build();
        return Response.noContent().cookie(expiration).build();
    }

    public record StatutSession(boolean authentifie, String nom) {
    }
}
