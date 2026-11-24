package dev.sylvain.planning.api;

import dev.sylvain.planning.service.compte.Compte;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.util.List;
import java.util.TreeSet;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * Session endpoints (issue #165, ADR 0093). The login itself is the OIDC
 * provider's authorization code flow, entered through {@link #oidcLogin} — or,
 * while the break-glass door is open, Quarkus' form authentication (the Angular
 * /login page posts {@code j_username}/{@code j_password} to
 * {@code /j_security_check}). This resource only adds what neither mechanism
 * ships: a status probe for the frontend guard, and a logout.
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

    private final SecurityIdentity identity;

    @Inject
    public AuthResource(SecurityIdentity identity) {
        this.identity = identity;
    }

    /**
     * Who the caller is, and <b>with which roles</b>: "signed in" and "may
     * administer" are no longer the same sentence once a provider signs in
     * people this application has no account for. The interface reads the roles
     * to explain a refusal instead of looping on the login.
     */
    @GET
    @Path("/me")
    public StatutSession me() {
        boolean authentifie = !identity.isAnonymous();
        return new StatutSession(
                authentifie,
                authentifie ? identity.getPrincipal().getName() : null,
                authentifie ? List.copyOf(new TreeSet<>(identity.getRoles())) : List.of(),
                compteId());
    }

    /** The id of the account the session is bound to; {@code null} for the configuration's own and for nobody. */
    private String compteId() {
        Compte compte = identity.getAttribute(Compte.IDENTITY_ATTRIBUTE);
        return compte == null ? null : compte.id();
    }

    /**
     * Entry point of the OIDC login, with no logic of its own: {@code @Authenticated}
     * makes an anonymous call fail the security check, Quarkus turns that into
     * the 302 to the authorization endpoint (PKCE, state cookie), and the
     * browser comes back here once the provider is satisfied. The visitor is
     * then sent to the root of the application — resolved against the base URI,
     * not the path, which would land on {@code /api/}.
     */
    @GET
    @Path("/oidc/login")
    @Authenticated
    public Response oidcLogin(@Context UriInfo uriInfo) {
        return Response.seeOther(uriInfo.getBaseUri().resolve("/")).build();
    }

    /**
     * Drops the local session cookie. Idempotent: logging out twice is fine.
     *
     * <p>For an OIDC session that is <b>not</b> where a logout ends: Quarkus
     * owns that cookie. The answer names the route that ends the provider's
     * session too, which the frontend navigates to — dropping a local cookie
     * alone would leave the provider ready to sign the visitor straight back in.
     * A form session (the break-glass account) has none.</p>
     */
    @POST
    @Path("/logout")
    @APIResponse(
            responseCode = "200",
            // Declared by hand: the method returns a Response (it has a cookie
            // to expire), which the schema generator cannot look inside.
            content = @Content(schema = @Schema(implementation = Deconnexion.class)))
    public Response logout() {
        NewCookie expiration = new NewCookie.Builder(COOKIE_SESSION)
                .value("")
                .path("/")
                .maxAge(0)
                .httpOnly(true)
                .build();
        boolean sessionOidc = !identity.isAnonymous() && identity.getPrincipal() instanceof JsonWebToken;
        String destination = sessionOidc ? "/api/auth/oidc/logout" : null;
        return Response.ok(new Deconnexion(destination)).cookie(expiration).build();
    }

    /**
     * @param authentifie whether a session is open at all
     * @param nom         the principal's name — the address of an OIDC session, or
     *                    {@code admin} for the break-glass account
     * @param roles       sorted, so the payload does not churn between calls
     * @param compteId    the id of the account of the application the session is bound to,
     *                    {@code null} for the break-glass account: what the interface compares
     *                    with, never the address
     */
    @Schema(requiredProperties = {"authentifie", "roles"})
    public record StatutSession(boolean authentifie, String nom, List<String> roles, String compteId) {}

    /** @param urlDeconnexion route ending the provider's session, {@code null} when there is none */
    public record Deconnexion(String urlDeconnexion) {}
}
