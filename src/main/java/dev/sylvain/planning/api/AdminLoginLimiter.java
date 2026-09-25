package dev.sylvain.planning.api;

import dev.sylvain.planning.config.ConfigAdminLogin;
import dev.sylvain.planning.config.TrustedProxies;
import dev.sylvain.planning.service.FailureLockout;
import io.quarkus.security.spi.runtime.AuthenticationFailureEvent;
import io.quarkus.vertx.http.runtime.filters.Filters;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Locks the admin form login out after a run of failures.
 *
 * <p>The application has a single account, {@code admin}, with no second
 * factor: one pair of credentials opens the personal data of ~150 people,
 * minors included. {@code /j_security_check} nonetheless accepted attempts at
 * the speed of the network — while revealing the MCP key already locked out
 * after five tries. This is the same lock, put where it was missing most.</p>
 *
 * <h2>How a failure and a success are told apart</h2>
 *
 * <p>Both answer with a redirect to the same page ({@code landing-page} and
 * {@code error-page} point at the same place), so neither the status nor
 * {@code Location} separates them. Both sides are therefore read elsewhere,
 * and not in the same place:</p>
 *
 * <ul>
 * <li><b>the failure</b> is the Quarkus {@link AuthenticationFailureEvent}
 * ({@code quarkus.security.events.enabled}), which carries the
 * {@link RoutingContext} of the attempt — hence its address;</li>
 * <li><b>the success</b> is read off the request itself. Quarkus does have a
 * successful-login event ({@code FormAuthenticationEvent}), but it carries
 * nothing except its own type: no HTTP context, so no address to give the
 * credit back to. A successful login is then recognised by what it leaves
 * behind — an identity set on the request, a session cookie in the response —
 * and either one of the two is enough.</li>
 * </ul>
 *
 * <p>The window runs from the <b>last</b> failure: a blocked request never
 * reaches authentication, so it produces no new one, and the lock does lift
 * {@code duree-blocage} after the last real attempt.</p>
 *
 * <p>In memory and per address: see {@link #address(RoutingContext)} for what
 * "address" means behind a proxy, and {@code docs/securite.md} for what is
 * left to the reverse proxy.</p>
 */
@ApplicationScoped
public class AdminLoginLimiter {

    /** Target of the Quarkus form login ({@code quarkus.http.auth.form.post-location} by default). */
    static final String CHEMIN_CONNEXION = "/j_security_check";

    /** After the security headers, before anything handles the request. */
    private static final int PRIORITE = 250;

    private final ConfigAdminLogin config;

    /** Read from the configuration so the two can never drift apart. */
    private final String nomCookieSession;

    @Inject
    public AdminLoginLimiter(
            ConfigAdminLogin config,
            @ConfigProperty(name = "quarkus.http.auth.form.cookie-name") String nomCookieSession) {
        this.config = config;
        this.nomCookieSession = nomCookieSession;
    }

    /**
     * Hard ceiling on the number of tracked addresses.
     *
     * <p>Only a successful login and a lockout check remove an entry, so an
     * address that fails once and never comes back stays counted for good — and
     * the map is fed by attempts, which is to say by anyone. Sweeping only the
     * <em>expired</em> entries would not bound it: an attacker inserting
     * distinct keys inside one window sees none of them expire, keeps them all,
     * and makes every later failure pay a full O(n) scan. So past the ceiling
     * the oldest entries are evicted outright, expired or not.</p>
     *
     * <p>Evicting a live counter is the lesser evil, and a narrow one: it costs
     * one address its lock, it takes a thousand distinct addresses inside
     * fifteen minutes to trigger, and the alternative is unbounded memory plus
     * quadratic work on the request path.</p>
     */
    private static final int ADRESSES_MAX = 1_000;

    /** Consecutive failures per address, shared in shape with the MCP key lock. */
    private final FailureLockout failures = new FailureLockout(ADRESSES_MAX);

    /**
     * The declared proxies, parsed once. Built here rather than lazily so a
     * malformed entry fails the boot: skipping it would leave the deployment
     * believing it had declared its proxy, while the lock quietly counted every
     * visitor on the proxy's own single counter.
     */
    private volatile TrustedProxies proxysFiables = TrustedProxies.NONE;

    public void register(@Observes Filters filtres) {
        proxysFiables = TrustedProxies.of(config.proxysFiables().orElse(List.of()));
        filtres.register(this::apply, PRIORITE);
    }

    private void apply(RoutingContext contexte) {
        if (!CHEMIN_CONNEXION.equals(contexte.normalizedPath())) {
            contexte.next();
            return;
        }
        String address = address(contexte);
        long attente = lockoutSeconds(address);
        if (attente > 0) {
            contexte.response()
                    .setStatusCode(429)
                    .putHeader("Retry-After", String.valueOf(attente))
                    .putHeader("Content-Type", "application/json;charset=UTF-8")
                    .end("{\"message\":\"Trop de tentatives de connexion : réessayez dans "
                            + Math.max(1, (attente + 59) / 60) + " minute(s).\"}");
            return;
        }
        contexte.addEndHandler(issue -> {
            if (issue.succeeded() && successfulLogin(contexte)) {
                failures.clear(address);
            }
        });
        contexte.next();
    }

    void onFailure(@Observes AuthenticationFailureEvent evenement) {
        Object contexte = evenement.getEventProperties().get(RoutingContext.class.getName());
        // The other mechanisms (MCP key, remote-user header, session already
        // open) raise the same event and have nothing to do with this lock.
        if (!(contexte instanceof RoutingContext routage) || !CHEMIN_CONNEXION.equals(routage.normalizedPath())) {
            return;
        }
        failures.recordFailure(address(routage), config.dureeBlocage());
    }

    /**
     * What a successful login leaves behind: the identity established on the
     * request, and the session cookie in the response. A failure produces
     * neither — which is exactly what
     * {@code AuthentificationAdminTest#unMauvaisMotDePasseEstRefuse} already
     * checks.
     */
    private boolean successfulLogin(RoutingContext contexte) {
        if (contexte.user() != null) {
            return true;
        }
        HttpServerResponse reponse = contexte.response();
        for (String entete : reponse.headers().getAll(HttpHeaders.SET_COOKIE)) {
            // An empty value is a cookie deletion, not a session.
            if (entete.startsWith(nomCookieSession + "=") && !entete.startsWith(nomCookieSession + "=;")) {
                return true;
            }
        }
        return false;
    }

    /** Seconds of lockout left, {@code 0} when the address may try its luck. */
    private long lockoutSeconds(String address) {
        return failures.lockoutSeconds(address, config.maxEchecs(), config.dureeBlocage());
    }

    /**
     * The address the lock counts against, which {@link ClientAddress} answers
     * for both this lock and the MCP rate limiter — reading
     * {@code X-Forwarded-For} from the right, and only when the peer is a
     * declared proxy. That method's javadoc carries the reasoning, including
     * why reading it from the left made this very lock useless.
     */
    private String address(RoutingContext contexte) {
        return ClientAddress.of(contexte, proxysFiables);
    }
}
