package dev.sylvain.planning.api;

import jakarta.inject.Inject;
import dev.sylvain.planning.config.ConfigConnexionAdmin;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.security.spi.runtime.AuthenticationFailureEvent;
import io.quarkus.vertx.http.runtime.filters.Filters;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

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
 * <p>In memory and per address: see {@link #adresse(RoutingContext)} for what
 * "address" means behind a proxy, and {@code docs/securite.md} for what is
 * left to the reverse proxy.</p>
 */
@ApplicationScoped
public class LimiteurConnexionsAdmin {

    /** Target of the Quarkus form login ({@code quarkus.http.auth.form.post-location} by default). */
    static final String CHEMIN_CONNEXION = "/j_security_check";

    /** After the security headers, before anything handles the request. */
    private static final int PRIORITE = 250;

    @Inject
    ConfigConnexionAdmin config;

    /** Read from the configuration so the two can never drift apart. */
    @ConfigProperty(name = "quarkus.http.auth.form.cookie-name")
    String nomCookieSession;

    private final Map<String, Echecs> parAdresse = new ConcurrentHashMap<>();

    /** Consecutive failures of one address, and the instant of the last one. */
    private record Echecs(int nombre, Instant dernier) {
    }

    public void enregistrer(@Observes Filters filtres) {
        filtres.register(this::appliquer, PRIORITE);
    }

    private void appliquer(RoutingContext contexte) {
        if (!CHEMIN_CONNEXION.equals(contexte.normalizedPath())) {
            contexte.next();
            return;
        }
        String adresse = adresse(contexte);
        long attente = secondesDeBlocage(adresse);
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
            if (issue.succeeded() && connexionReussie(contexte)) {
                parAdresse.remove(adresse);
            }
        });
        contexte.next();
    }

    void surEchec(@Observes AuthenticationFailureEvent evenement) {
        Object contexte = evenement.getEventProperties().get(RoutingContext.class.getName());
        // The other mechanisms (MCP key, remote-user header, session already
        // open) raise the same event and have nothing to do with this lock.
        if (!(contexte instanceof RoutingContext routage)
                || !CHEMIN_CONNEXION.equals(routage.normalizedPath())) {
            return;
        }
        Instant maintenant = Instant.now();
        parAdresse.compute(adresse(routage), (ignore, courant) -> {
            if (courant == null || courant.dernier().plus(config.dureeBlocage()).isBefore(maintenant)) {
                return new Echecs(1, maintenant);
            }
            return new Echecs(courant.nombre() + 1, maintenant);
        });
    }

    /**
     * What a successful login leaves behind: the identity established on the
     * request, and the session cookie in the response. A failure produces
     * neither — which is exactly what
     * {@code AuthentificationAdminTest#unMauvaisMotDePasseEstRefuse} already
     * checks.
     */
    private boolean connexionReussie(RoutingContext contexte) {
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
    private long secondesDeBlocage(String adresse) {
        Echecs echecs = parAdresse.get(adresse);
        if (echecs == null || echecs.nombre() < config.maxEchecs()) {
            return 0;
        }
        long restant = Duration.between(Instant.now(), echecs.dernier().plus(config.dureeBlocage())).toSeconds();
        if (restant <= 0) {
            parAdresse.remove(adresse);
            return 0;
        }
        return Math.max(restant, 1);
    }

    /**
     * The address the proxy announces wins over the one of the connection:
     * behind a reverse proxy every request comes from the same address, and
     * counting on that would let the first attacker who shows up lock everybody
     * else out. This header is only worth trusting when the origin cannot be
     * reached without going through the proxy — the same condition as
     * {@code proxy-address-forwarding}, which {@code docs/securite.md} makes a
     * deployment prerequisite.
     */
    private static String adresse(RoutingContext contexte) {
        String transmise = contexte.request().getHeader("X-Forwarded-For");
        if (transmise != null && !transmise.isBlank()) {
            return transmise.split(",")[0].trim();
        }
        return contexte.request().remoteAddress() == null
                ? "inconnue"
                : contexte.request().remoteAddress().hostAddress();
    }
}
