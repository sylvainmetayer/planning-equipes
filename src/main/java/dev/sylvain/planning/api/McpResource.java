package dev.sylvain.planning.api;

import dev.sylvain.planning.config.ConfigMcp;
import dev.sylvain.planning.config.ConfigOidc;
import dev.sylvain.planning.mcp.McpPrompts;
import dev.sylvain.planning.mcp.McpPrompts.PromptExpose;
import dev.sylvain.planning.service.mcp.McpKeyService;
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
 */
/*
 * Explicitly @ApplicationScoped, unlike the neighbouring resources: the
 * lock-out counter below is instance state, and it would silently do nothing
 * on a per-request instance. Making the scope depend on a framework default
 * would turn a rate limiter into decoration.
 */
@ApplicationScoped
@InstanceOnly
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

    private final ConfigMcp mcp;

    private final McpPrompts prompts;

    private final McpKeyService keys;

    private final ConfigOidc oidc;

    /**
     * The admin password, read from the very property the embedded security
     * realm authenticates against — so this can never drift out of step with
     * the real credential, which a second copy of {@code ADMIN_PASSWORD} would.
     */
    private final Optional<String> motDePasseAdmin;

    @Inject
    public McpResource(
            ConfigMcp mcp,
            McpPrompts prompts,
            McpKeyService keys,
            ConfigOidc oidc,
            @ConfigProperty(name = "quarkus.security.users.embedded.users.admin") Optional<String> motDePasseAdmin) {
        this.mcp = mcp;
        this.prompts = prompts;
        this.keys = keys;
        this.oidc = oidc;
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

    /** Exchanges the admin password for the API cle. {@code 401} on a wrong password, {@code 429} once locked out. */
    @POST
    @Path("/cle")
    public synchronized Response reveal(DemandeRevelation demande) {
        Response refus = checkPassword(demande == null ? null : demande.motDePasse());
        if (refus != null) {
            return refus;
        }
        if (keys.status().origin() == McpKeyService.Origin.GENERATED) {
            // Only its fingerprint is kept: a generated key is shown once, when it is generated.
            return Response.status(Response.Status.CONFLICT).build();
        }
        if (mcp.apiKey().isEmpty() || mcp.apiKey().get().isBlank()) {
            // Right password, nothing to reveal: 404 rather than an empty string.
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(new McpKey(
                        mcp.apiKey().get(),
                        mcp.pangolin().accessTokenId().filter(v -> !v.isBlank()).orElse(null),
                        mcp.pangolin().accessToken().filter(v -> !v.isBlank()).orElse(null)))
                .build();
    }

    /**
     * Generates a new key, shown once (ADR 0097): the previous one - the
     * configured one the first time - keeps working for {@code recouvrementMinutes}
     * (zero: it stops at once). Same password gate, and same lock-out, as the reveal.
     */
    @POST
    @Path("/cle/rotation")
    public synchronized Response rotate(DemandeRotation demande) {
        Response refus = checkPassword(demande == null ? null : demande.motDePasse());
        if (refus != null) {
            return refus;
        }
        long recouvrement = demande.recouvrementMinutes() == null ? 0 : demande.recouvrementMinutes();
        McpKeyService.Rotation rotation = keys.rotate(recouvrement);
        return Response.ok(new GeneratedMcpKey(
                        rotation.key(),
                        rotation.previousExpiresAt(),
                        mcp.pangolin().accessTokenId().filter(v -> !v.isBlank()).orElse(null),
                        mcp.pangolin().accessToken().filter(v -> !v.isBlank()).orElse(null)))
                .build();
    }

    /** {@code null} when the password is the admin's; else the refusal to answer, the lock-out counted. */
    private Response checkPassword(String motDePasse) {
        if (blocageJusqua != null && Instant.now().isBefore(blocageJusqua)) {
            return Response.status(429).build();
        }
        String attendu = motDePasseAdmin.orElse("");
        String presente = motDePasse == null ? "" : motDePasse;
        if (attendu.isBlank() || !constantTimeEquals(attendu, presente)) {
            if (++essaisRates >= MAX_ESSAIS) {
                blocageJusqua = Instant.now().plus(DUREE_BLOCAGE);
                essaisRates = 0;
            }
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        essaisRates = 0;
        blocageJusqua = null;
        return null;
    }

    /** Constant-time comparison: a wrong password must not leak its correct prefix through timing. */
    private static boolean constantTimeEquals(String attendu, String presente) {
        return MessageDigest.isEqual(
                attendu.getBytes(StandardCharsets.UTF_8), presente.getBytes(StandardCharsets.UTF_8));
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
    public record DemandeRotation(String motDePasse, Long recouvrementMinutes) {}

    /** The new key, in clear, once; {@code ancienneExpireLe} is {@code null} when no key was in force. */
    public record GeneratedMcpKey(
            String cle, Instant ancienneExpireLe, String pangolinAccessTokenId, String pangolinAccessToken) {}

    public record DemandeRevelation(String motDePasse) {}

    /**
     * @param pangolinAccessTokenId {@code null} unless {@code planning.mcp.pangolin.access-token-id} is set
     * @param pangolinAccessToken   {@code null} unless {@code planning.mcp.pangolin.access-token} is set
     */
    public record McpKey(String cle, String pangolinAccessTokenId, String pangolinAccessToken) {}
}
