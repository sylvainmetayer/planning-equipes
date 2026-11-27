package dev.sylvain.planning.api;

import dev.sylvain.planning.service.FailureLockout;
import dev.sylvain.planning.service.compte.Compte;
import dev.sylvain.planning.service.compte.CompteAccesService;
import dev.sylvain.planning.service.compte.SessionOpenings;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import io.vertx.ext.web.RoutingContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    private static final String MESSAGE = "message";

    /** Must match {@code quarkus.http.auth.form.cookie-name}. */
    static final String COOKIE_SESSION = "planning-session";

    private final SecurityIdentity identity;
    private final CompteAccesService acces;
    private final AdminLoginLimiter limiteur;
    private final SessionOpenings openings;

    @Inject
    public AuthResource(
            SecurityIdentity identity, CompteAccesService acces, AdminLoginLimiter limiteur, SessionOpenings openings) {
        this.identity = identity;
        this.acces = acces;
        this.limiteur = limiteur;
        this.openings = openings;
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
        boolean authentifie =
                !identity.isAnonymous() && !Boolean.TRUE.equals(identity.getAttribute(Compte.STALE_ATTRIBUTE));
        return new StatutSession(
                authentifie,
                authentifie
                        ? CompteAccesService.addressOf(identity.getPrincipal().getName())
                        : null,
                authentifie ? List.copyOf(new TreeSet<>(identity.getRoles())) : List.of(),
                compteId());
    }

    /** The id of the account the session is bound to; {@code null} for the configuration's own and for nobody. */
    private String compteId() {
        Compte compte = identity.getAttribute(Compte.IDENTITY_ATTRIBUTE);
        return compte == null ? null : compte.id();
    }

    /**
     * The password step of a local sign-in (ADR 0094). It opens no session: it
     * answers with the one-time challenge the form post then exchanges — and
     * first mails the code, unless the operator turned the second step off. A
     * failure is one answer whatever its cause (unknown address, wrong password,
     * deactivated account, no password yet), counted by the same lock as the
     * form.
     */
    @POST
    @Path("/connexion")
    @Consumes(MediaType.APPLICATION_JSON)
    @APIResponse(
            responseCode = "200",
            // Declared by hand: the refusals carry a Response of their own.
            content = @Content(schema = @Schema(implementation = CompteAccesService.Challenge.class)))
    public Response passwordStep(Credentials credentials, @Context RoutingContext routage) {
        // The lock is asked here too, not only by the filter: a path the filter
        // does not recognise must not buy unlimited bcrypt.
        String email = credentials == null ? null : credentials.email();
        long wait = limiteur.reservePasswordStep(routage, email);
        if (wait > 0) {
            return Response.status(429)
                    .header("Retry-After", wait)
                    .entity(Map.of(
                            MESSAGE,
                            "Trop de tentatives de connexion : réessayez dans " + Math.max(1, (wait + 59) / 60)
                                    + " minute(s)."))
                    .build();
        }
        FailureLockout.Outcome outcome = FailureLockout.Outcome.NEITHER;
        try {
            Optional<CompteAccesService.Challenge> challenge =
                    acces.open(email, credentials == null ? null : credentials.password());
            if (challenge.isEmpty()) {
                outcome = FailureLockout.Outcome.FAILURE;
                return Response.status(401)
                        .entity(Map.of(MESSAGE, "Adresse ou mot de passe incorrect."))
                        .build();
            }
            outcome = FailureLockout.Outcome.SUCCESS;
            return Response.ok(challenge.get()).build();
        } catch (CompteAccesService.TooManyRequests e) {
            return Response.status(429)
                    .header("Retry-After", e.secondsBeforeNextTry())
                    .entity(Map.of(MESSAGE, e.getMessage()))
                    .build();
        } finally {
            limiteur.settlePasswordStep(routage, email, outcome);
        }
    }

    /**
     * « Mot de passe oublié ». The same {@code 204} whether the address has an
     * account or not.
     */
    @POST
    @Path("/mot-de-passe/oubli")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response forgotPassword(ForgotRequest request) {
        acces.forgotAsync(request == null ? null : request.email());
        return Response.noContent().build();
    }

    /** Chooses the password with a single-use link (invitation or reset). */
    @POST
    @Path("/mot-de-passe")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response choosePassword(PasswordChoice choice) {
        acces.choose(choice == null ? null : choice.token(), choice == null ? null : choice.password());
        return Response.noContent().build();
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
    public Response oidcLogin(@Context UriInfo uriInfo, @QueryParam("retour") String retour) {
        return Response.seeOther(uriInfo.getBaseUri().resolve(safeReturn(retour)))
                .build();
    }

    /**
     * A path of this application, never another site: anything else goes back to
     * the root. Parsed rather than pattern-matched, so a control character (a tab a
     * browser would drop from {@code /\t/evil}) or a backslash is refused too,
     * instead of reaching {@code resolve} and its 500.
     */
    public static String safeReturn(String retour) {
        if (retour == null || !retour.startsWith("/") || retour.startsWith("//")) {
            return "/";
        }
        try {
            URI uri = new URI(retour);
            return uri.getScheme() == null && uri.getRawAuthority() == null ? retour : "/";
        } catch (URISyntaxException e) {
            return "/";
        }
    }

    /**
     * Drops the local session cookie and logs its session out on the server:
     * the cookie is stateless and stays readable until it expires, so a copy of
     * it — this value or any other the session took — is refused from now on
     * ({@link SessionOpenings#logOut}). Only this session: the account's other
     * devices stay signed in. Idempotent: logging out twice is fine.
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
    public Response logout(@Context HttpHeaders headers) {
        // The Cookie header as sent, not the jar: the form mechanism may already have put a renewed value there.
        Cookie session = headers.getCookies().get(COOKIE_SESSION);
        openings.logOut(session == null ? null : session.getValue());
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

    public record Credentials(String email, String password) {}

    public record ForgotRequest(String email) {}

    public record PasswordChoice(String token, String password) {}

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
