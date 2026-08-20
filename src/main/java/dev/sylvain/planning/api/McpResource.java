package dev.sylvain.planning.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * What the MCP page of the interface needs and the MCP transport itself
 * cannot tell it: whether this deployment has an API key at all, and — behind
 * a re-typed admin password — what that key is.
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
 * open tab is materially worse than the rest of the interface, since the key
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

    @ConfigProperty(name = "planning.mcp.api-key")
    Optional<String> apiKey;

    @ConfigProperty(name = "planning.mcp.api-key-header", defaultValue = "X-MCP-Api-Key")
    String headerName;

    /**
     * The admin password, read from the very property the embedded security
     * realm authenticates against — so this can never drift out of step with
     * the real credential, which a second copy of {@code ADMIN_PASSWORD} would.
     */
    @ConfigProperty(name = "quarkus.security.users.embedded.users.admin")
    Optional<String> motDePasseAdmin;

    private int essaisRates;
    private Instant blocageJusqua;

    /**
     * Whether a key is configured, and under which header it travels. Never
     * the key itself: this is what lets the page warn "MCP inutilisable en
     * l'état" instead of letting an operator configure a client that will
     * only ever get 401s.
     */
    @GET
    @Path("/statut")
    public StatutMcp statut() {
        return new StatutMcp(apiKey.isPresent() && !apiKey.get().isBlank(), headerName);
    }

    /** Exchanges the admin password for the API key. {@code 401} on a wrong password, {@code 429} once locked out. */
    @POST
    @Path("/cle")
    public synchronized Response reveler(DemandeRevelation demande) {
        if (blocageJusqua != null && Instant.now().isBefore(blocageJusqua)) {
            return Response.status(429).build();
        }
        String attendu = motDePasseAdmin.orElse("");
        String presente = demande == null || demande.motDePasse() == null ? "" : demande.motDePasse();
        if (attendu.isBlank() || !egales(attendu, presente)) {
            if (++essaisRates >= MAX_ESSAIS) {
                blocageJusqua = Instant.now().plus(DUREE_BLOCAGE);
                essaisRates = 0;
            }
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        essaisRates = 0;
        blocageJusqua = null;
        if (apiKey.isEmpty() || apiKey.get().isBlank()) {
            // Right password, nothing to reveal: 404 rather than an empty
            // string, so the page can tell "clé absente" from "clé vide".
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(new CleMcp(apiKey.get())).build();
    }

    /** Constant-time comparison: a wrong password must not leak its correct prefix through timing. */
    private static boolean egales(String attendu, String presente) {
        return MessageDigest.isEqual(attendu.getBytes(StandardCharsets.UTF_8),
                presente.getBytes(StandardCharsets.UTF_8));
    }

    public record StatutMcp(boolean configuree, String header) {
    }

    public record DemandeRevelation(String motDePasse) {
    }

    public record CleMcp(String cle) {
    }
}
