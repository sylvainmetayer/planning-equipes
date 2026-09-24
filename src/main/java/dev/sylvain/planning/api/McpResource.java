package dev.sylvain.planning.api;

import dev.sylvain.planning.config.ConfigMcp;
import dev.sylvain.planning.mcp.McpPrompts;
import dev.sylvain.planning.mcp.McpPrompts.PromptExpose;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * What the MCP page of the interface needs and the MCP transport itself
 * cannot tell it: whether this deployment has an API cle at all, and — behind
 * a re-typed admin password — what that cle is.
 *
 * <p>Served under {@code /api/mcp} (see {@code quarkus.rest.path}), which is a
 * different path from the {@code /mcp} transport and therefore falls under the
 * ordinary {@code admin-api} policy: an admin session is required before any
 * of this is reachable.</p>
 *
 * <h2>Why re-ask for the password</h2>
 * <p>The session alone already proves "an admin". Revealing a long-lived
 * shared secret that grants full write access to the referentials and the
 * solver deserves more than that: it is the one action where an unattended
 * open tab is materially worse than the rest of the interface, since the cle
 * outlives the session it was copied from. Re-typing the password binds the
 * reveal to a person present at the keyboard, which is exactly the threat
 * model — not an anonymous attacker, who never gets this far.</p>
 *
 * <p>A Keycloak session has no password this application could re-ask
 * (ADR 0049). The same fact — someone at the keyboard, just now — is read off
 * the token instead: its {@code auth_time}, the moment the person last proved
 * who they are to the realm, second factor included, must be less than
 * {@link #FRAICHEUR_CONNEXION} old. A session older than that is told to sign
 * out and back in. The password path stays for the break-glass account.</p>
 */
/*
 * Explicitly @ApplicationScoped, unlike the neighbouring resources: the
 * lock-out counter below is instance state, and it would silently do nothing
 * on a per-request instance. Making the scope depend on a framework default
 * would turn a rate limiter into decoration.
 */
@ApplicationScoped
@Path("/mcp")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class McpResource {

    /**
     * Attempts allowed before the endpoint stops answering, and for how long.
     * Modest on purpose: the caller is already an authenticated admin, so this
     * guards against someone poking at a borrowed session rather than against
     * a brute-force run — which the admin login itself would have to stop
     * first.
     */
    static final int MAX_ESSAIS = 5;

    static final Duration DUREE_BLOCAGE = Duration.ofMinutes(5);

    /** How recent a Keycloak authentication must be to reveal the cle. */
    static final Duration FRAICHEUR_CONNEXION = Duration.ofMinutes(5);

    private final SecurityIdentity identity;

    private final ConfigMcp mcp;

    private final McpPrompts prompts;

    /**
     * The admin password, read from the very property the embedded security
     * realm authenticates against — so this can never drift out of step with
     * the real credential, which a second copy of {@code ADMIN_PASSWORD} would.
     */
    private final Optional<String> motDePasseAdmin;

    @Inject
    public McpResource(
            SecurityIdentity identity,
            ConfigMcp mcp,
            McpPrompts prompts,
            @ConfigProperty(name = "quarkus.security.users.embedded.users.admin") Optional<String> motDePasseAdmin) {
        this.identity = identity;
        this.mcp = mcp;
        this.prompts = prompts;
        this.motDePasseAdmin = motDePasseAdmin;
    }

    private int essaisRates;
    private Instant blocageJusqua;

    /**
     * Whether a cle is configured, and under which header it travels. Never
     * the cle itself: this is what lets the page warn "MCP inutilisable en
     * l'état" instead of letting an operator configure a client that will
     * only ever get 401s.
     */
    @GET
    @Path("/statut")
    public StatutMcp statut() {
        return new StatutMcp(
                mcp.apiKey().isPresent() && !mcp.apiKey().get().isBlank(),
                mcp.apiKeyHeader(),
                keycloakSession().isPresent());
    }

    /**
     * The prompts the MCP server announces, so the page can offer them to a
     * client that does not support prompts.
     *
     * <p>Read from the server rather than written into the page: the page used
     * to carry its own copies, and one of them named a tool this application
     * has never exposed. Nobody could have noticed — a prompt is prose until
     * somebody pastes it.</p>
     */
    @GET
    @Path("/prompts")
    public List<PromptExpose> prompts() {
        return prompts.catalogue();
    }

    /**
     * Exchanges the admin password — or, for a Keycloak session, a recent
     * sign-in — for the API cle. {@code 401} on a wrong password or a sign-in
     * too old, {@code 429} once locked out.
     */
    @POST
    @Path("/cle")
    public synchronized Response reveal(DemandeRevelation demande) {
        if (blocageJusqua != null && Instant.now().isBefore(blocageJusqua)) {
            return Response.status(429).build();
        }
        Optional<JsonWebToken> jeton = keycloakSession();
        if (jeton.isPresent()) {
            if (!recentSignIn(jeton.get(), Instant.now())) {
                return Response.status(Response.Status.UNAUTHORIZED)
                        .entity(new ValidationError("Déconnectez-vous puis reconnectez-vous : révéler la clé exige"
                                + " une connexion de moins de " + FRAICHEUR_CONNEXION.toMinutes() + " minutes."))
                        .build();
            }
            return revealedKey();
        }
        String attendu = motDePasseAdmin.orElse("");
        String presente = demande == null || demande.motDePasse() == null ? "" : demande.motDePasse();
        if (attendu.isBlank() || !constantTimeEquals(attendu, presente)) {
            if (++essaisRates >= MAX_ESSAIS) {
                blocageJusqua = Instant.now().plus(DUREE_BLOCAGE);
                essaisRates = 0;
            }
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        essaisRates = 0;
        blocageJusqua = null;
        return revealedKey();
    }

    private Response revealedKey() {
        if (mcp.apiKey().isEmpty() || mcp.apiKey().get().isBlank()) {
            // Right password, nothing to reveal: 404 rather than an empty
            // string, so the page can tell "clé absente" from "clé hasNoChange".
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(new McpKey(
                        mcp.apiKey().get(),
                        mcp.pangolin().accessTokenId().filter(v -> !v.isBlank()).orElse(null),
                        mcp.pangolin().accessToken().filter(v -> !v.isBlank()).orElse(null)))
                .build();
    }

    /** The Keycloak token of the caller, empty for the break-glass account or no session. */
    private Optional<JsonWebToken> keycloakSession() {
        return identity != null && !identity.isAnonymous() && identity.getPrincipal() instanceof JsonWebToken jeton
                ? Optional.of(jeton)
                : Optional.empty();
    }

    /**
     * Whether the person proved who they are to the realm less than
     * {@link #FRAICHEUR_CONNEXION} ago. A token without {@code auth_time}
     * cannot say, and is refused.
     */
    static boolean recentSignIn(JsonWebToken jeton, Instant maintenant) {
        Object authTime = jeton.getClaim("auth_time");
        if (!(authTime instanceof Number secondes)) {
            return false;
        }
        return Instant.ofEpochSecond(secondes.longValue())
                .plus(FRAICHEUR_CONNEXION)
                .isAfter(maintenant);
    }

    /** Constant-time comparison: a wrong password must not leak its correct prefix through timing. */
    private static boolean constantTimeEquals(String attendu, String presente) {
        return MessageDigest.isEqual(
                attendu.getBytes(StandardCharsets.UTF_8), presente.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * @param revelationParReconnexion the caller holds a Keycloak session: the
     *                                 cle is revealed after a recent sign-in,
     *                                 not against a password
     */
    @Schema(requiredProperties = {"configuree", "revelationParReconnexion"})
    public record StatutMcp(boolean configuree, String header, boolean revelationParReconnexion) {}

    public record DemandeRevelation(String motDePasse) {}

    /**
     * @param pangolinAccessTokenId {@code null} unless {@code planning.mcp.pangolin.access-token-id} is set
     * @param pangolinAccessToken   {@code null} unless {@code planning.mcp.pangolin.access-token} is set
     */
    public record McpKey(String cle, String pangolinAccessTokenId, String pangolinAccessToken) {}
}
