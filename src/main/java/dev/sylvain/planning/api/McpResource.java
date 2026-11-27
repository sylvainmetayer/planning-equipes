package dev.sylvain.planning.api;

import dev.sylvain.planning.config.ConfigMcp;
import dev.sylvain.planning.config.ConfigOidc;
import dev.sylvain.planning.mcp.McpPrompts;
import dev.sylvain.planning.mcp.McpPrompts.PromptExpose;
import dev.sylvain.planning.service.compte.RecentAuthentication;
import dev.sylvain.planning.service.mcp.McpKeyService;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * What the MCP page of the interface needs and the MCP transport itself
 * cannot tell it: whether this deployment has an API key at all, and — behind
 * a recent sign-in — what that key is, or a new one.
 *
 * <p>Served under {@code /api/mcp} (see {@code quarkus.rest.path}), which is a
 * different path from the {@code /mcp} transport and therefore falls under the
 * ordinary {@code admin-api} policy: an admin session is required before any
 * of this is reachable.</p>
 *
 * <h2>Why a recent sign-in</h2>
 * <p>The session alone already proves "an admin". Revealing or generating a
 * long-lived shared secret that grants full write access deserves more than
 * that: an unattended open tab is materially worse here than anywhere else,
 * since the key outlives the session it was copied from. A sign-in within the
 * last minutes binds the gesture to a person present at the keyboard — for an
 * administrator signed in through the provider or a local account too, who
 * have no configuration password to retype (ADR 0097). The refusal is a
 * {@code 409} with the code {@code RECONNEXION_REQUISE_<mode>}, which the page
 * answers by offering the way to sign in again.</p>
 */
@ApplicationScoped
@InstanceOnly
@Path("/mcp")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class McpResource {

    private final ConfigMcp mcp;

    private final McpPrompts prompts;

    private final McpKeyService keys;

    private final ConfigOidc oidc;

    private final RecentAuthentication recent;

    private final SecurityIdentity identity;

    @Inject
    public McpResource(
            ConfigMcp mcp,
            McpPrompts prompts,
            McpKeyService keys,
            ConfigOidc oidc,
            RecentAuthentication recent,
            SecurityIdentity identity) {
        this.mcp = mcp;
        this.prompts = prompts;
        this.keys = keys;
        this.oidc = oidc;
        this.recent = recent;
        this.identity = identity;
    }

    /**
     * Whether a cle is configured, and under which header it travels. Never
     * the cle itself: this is what lets the page warn "MCP inutilisable en
     * l'état" instead of letting an operator configure a client that will
     * only ever get 401s.
     */
    @GET
    @Path("/statut")
    public StatutMcp statut() {
        McpKeyService.Status etat = keys.status();
        return new StatutMcp(
                etat.origin() != McpKeyService.Origin.NONE,
                mcp.apiKeyHeader(),
                etat.origin().name(),
                etat.generatedAt(),
                etat.previousExpiresAt(),
                oidc.enabled());
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

    /** The configured key, to a caller who signed in recently: {@code 409 RECONNEXION_REQUISE_<mode>} otherwise. */
    @POST
    @Path("/cle")
    public Response reveal(@CookieParam("planning-session") String session) {
        Response refus = reauthenticationRefusal(session);
        if (refus != null) {
            return refus;
        }
        if (keys.status().origin() == McpKeyService.Origin.GENERATED) {
            // Only its fingerprint is kept: a generated key is shown once, when it is generated.
            return Response.status(Response.Status.CONFLICT).build();
        }
        Optional<String> configured = keys.configured();
        if (configured.isEmpty()) {
            // Recent enough, nothing to reveal (or a demo instance): 404 rather than an empty string.
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(new McpKey(
                        configured.get(),
                        mcp.pangolin().accessTokenId().filter(v -> !v.isBlank()).orElse(null),
                        mcp.pangolin().accessToken().filter(v -> !v.isBlank()).orElse(null)))
                .build();
    }

    /**
     * Generates a new key, shown once (ADR 0097): the previous one — the
     * configured one the first time — keeps working for {@code recouvrementMinutes}
     * (zero: it stops at once). Same recent sign-in as the reveal.
     */
    @POST
    @Path("/cle/rotation")
    public synchronized Response rotate(DemandeRotation demande, @CookieParam("planning-session") String session) {
        Response refus = reauthenticationRefusal(session);
        if (refus != null) {
            return refus;
        }
        long recouvrement =
                demande == null || demande.recouvrementMinutes() == null ? 0 : demande.recouvrementMinutes();
        McpKeyService.Rotation rotation = keys.rotate(recouvrement);
        return Response.ok(new GeneratedMcpKey(
                        rotation.key(),
                        rotation.previousExpiresAt(),
                        mcp.pangolin().accessTokenId().filter(v -> !v.isBlank()).orElse(null),
                        mcp.pangolin().accessToken().filter(v -> !v.isBlank()).orElse(null)))
                .build();
    }

    /** {@code null} when the caller signed in recently; else the {@code 409} that says how to sign in again. */
    private Response reauthenticationRefusal(String session) {
        return recent.reauthenticationRequired(identity, session)
                .map(mode -> Response.status(Response.Status.CONFLICT)
                        .entity(new ReauthenticationRequired(
                                "RECONNEXION_REQUISE_" + mode.name(),
                                mode.name(),
                                "Reconnectez-vous pour cette action : la dernière connexion est trop ancienne."))
                        .build())
                .orElse(null);
    }

    /**
     * @param origine {@code NONE}, {@code CONFIG} (the environment variable) or {@code GENERATED} (from this screen)
     * @param genereeLe when the generated key was made, {@code null} otherwise
     * @param ancienneExpireLe when the key being retired stops working, {@code null} when none is
     * @param oauth whether a sign-in provider lets an MCP client connect by OAuth
     */
    @Schema(requiredProperties = {"configuree", "origine", "oauth"})
    public record StatutMcp(
            boolean configuree,
            String header,
            String origine,
            Instant genereeLe,
            Instant ancienneExpireLe,
            boolean oauth) {}

    /** @param recouvrementMinutes how long the old key keeps working; absent or zero: it stops at once */
    public record DemandeRotation(Long recouvrementMinutes) {}

    /** The refusal of a sign-in that is too old; {@code mode} says which way back the page offers. */
    public record ReauthenticationRequired(String code, String mode, String message) {}

    /** The new key, in clear, once; {@code ancienneExpireLe} is {@code null} when no key was in force. */
    public record GeneratedMcpKey(
            String cle, Instant ancienneExpireLe, String pangolinAccessTokenId, String pangolinAccessToken) {}

    /**
     * @param pangolinAccessTokenId {@code null} unless {@code planning.mcp.pangolin.access-token-id} is set
     * @param pangolinAccessToken   {@code null} unless {@code planning.mcp.pangolin.access-token} is set
     */
    public record McpKey(String cle, String pangolinAccessTokenId, String pangolinAccessToken) {}
}
