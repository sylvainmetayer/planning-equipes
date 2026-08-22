package dev.sylvain.planning.service;

import jakarta.inject.Inject;
import dev.sylvain.planning.config.ConfigEspaceCode;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Rate limit on the espace animateur access-code requests.
 *
 * <p>Without it, {@code POST /api/espace-animateur/{token}/code} is a mail send
 * anybody holding the link can trigger in a loop: it drowns the animateur's
 * inbox under codes, exhausts the SMTP server quota, and resets the code
 * attempt counter to five on every request.</p>
 *
 * <p>What is counted are the codes that were <b>never used</b>: opening the
 * session with the code received clears the counter. An animateur logging in
 * normally, even often, therefore never reaches the ceiling — only the
 * accumulation of requests with no follow-up, which is exactly the abuse, leads
 * there. The window slides from the first request left without effect.</p>
 *
 * <p>In memory, like the {@code McpResource} lockout: the application is
 * single-instance, and a restart is not within reach of the attacker this
 * counter aims at. Rate limiting per IP address belongs to the reverse proxy —
 * see {@code docs/securite.md}.</p>
 */
@ApplicationScoped
public class CodeRequestLimiter {

    /** What the request tells the caller: go ahead, or come back in so many seconds. */
    public record Verdict(boolean autorise, long secondsBeforeNextTry) {

        static Verdict ok() {
            return new Verdict(true, 0);
        }
    }

    @Inject
    ConfigEspaceCode config;

    private final Map<String, Fenetre> parAnimateur = new ConcurrentHashMap<>();

    /** Unconsumed requests, and the start of the window counting them. */
    private record Fenetre(int demandes, Instant debut) {
    }

    /**
     * Consumes one rate-limit token for {@code key}. A negative verdict carries
     * the delay left before the window reopens, ready to be used as is for
     * {@code Retry-After}.
     */
    public Verdict request(String key) {
        Instant maintenant = Instant.now();
        Fenetre apres = parAnimateur.compute(key, (ignore, courante) -> {
            if (courante == null || courante.debut().plus(config.fenetre()).isBefore(maintenant)) {
                return new Fenetre(1, maintenant);
            }
            return new Fenetre(courante.demandes() + 1, courante.debut());
        });
        if (apres.demandes() <= config.maxDemandes()) {
            return Verdict.ok();
        }
        long restant = Duration.between(maintenant, apres.debut().plus(config.fenetre())).toSeconds();
        return new Verdict(false, Math.max(restant, 1));
    }

    /** The code was used: the run of requests with no follow-up stops there. */
    public void oublier(String key) {
        parAnimateur.remove(key);
    }
}
