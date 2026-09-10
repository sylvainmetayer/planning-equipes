package dev.sylvain.planning.api;

import dev.sylvain.planning.config.ConfigAdminLogin;
import dev.sylvain.planning.config.TrustedProxies;
import io.quarkus.security.spi.runtime.AuthenticationFailureEvent;
import io.quarkus.vertx.http.runtime.filters.Filters;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.net.SocketAddress;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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

    @Inject
    ConfigAdminLogin config;

    /** Read from the configuration so the two can never drift apart. */
    @ConfigProperty(name = "quarkus.http.auth.form.cookie-name")
    String nomCookieSession;

    private final Map<String, Echecs> parAdresse = new ConcurrentHashMap<>();

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

    /** Consecutive failures of one address, and the instant of the last one. */
    private record Echecs(int nombre, Instant dernier) {}

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
                parAdresse.remove(address);
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
        Instant maintenant = Instant.now();
        if (parAdresse.size() >= ADRESSES_MAX) {
            evictDown(maintenant);
        }
        parAdresse.compute(address(routage), (ignore, courant) -> {
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

    /**
     * Brings the map back under its ceiling: expired entries first, then, if
     * that is not enough, the oldest ones.
     */
    private void evictDown(Instant maintenant) {
        parAdresse
                .entrySet()
                .removeIf(entree ->
                        entree.getValue().dernier().plus(config.dureeBlocage()).isBefore(maintenant));
        if (parAdresse.size() < ADRESSES_MAX) {
            return;
        }
        parAdresse.entrySet().stream()
                .sorted(Comparator.comparing(entree -> entree.getValue().dernier()))
                .limit(Math.max(1, parAdresse.size() - ADRESSES_MAX + 1))
                .map(Map.Entry::getKey)
                .toList()
                .forEach(parAdresse::remove);
    }

    /** Seconds of lockout left, {@code 0} when the address may try its luck. */
    private long lockoutSeconds(String address) {
        Echecs echecs = parAdresse.get(address);
        if (echecs == null || echecs.nombre() < config.maxEchecs()) {
            return 0;
        }
        long restant = Duration.between(Instant.now(), echecs.dernier().plus(config.dureeBlocage()))
                .toSeconds();
        if (restant <= 0) {
            parAdresse.remove(address);
            return 0;
        }
        return Math.max(restant, 1);
    }

    /**
     * The address the lock counts against.
     *
     * <p>Two steps, and the first is the one that matters. <b>The peer must be a
     * declared proxy</b> — read from {@code connection().remoteAddress()}, not
     * {@code request().remoteAddress()}, which {@code proxy-address-forwarding}
     * has already rewritten with the client's own forged value. If the machine
     * actually connecting is not in
     * {@code planning.auth.connexion.proxys-fiables} — as a literal address or
     * within one of its CIDR blocks — {@code X-Forwarded-For} is whatever that
     * machine chose to write, so it is ignored entirely and the connection
     * address is counted. Left empty — the default — no header is
     * ever trusted, which is right for a deployment with no proxy and safe for
     * one whose proxies have not been declared.</p>
     *
     * <p>When the peer <em>is</em> a declared proxy, the header is walked from
     * the <b>right</b>, skipping further declared proxies, and the first
     * remaining entry wins: that is the address the last trusted hop actually
     * observed, appended by it. Everything to its left is client-supplied text.</p>
     *
     * <p>Reading from the left is what made the lock useless. A proxy
     * <em>appends</em> its entry rather than replacing the header, so the
     * leftmost element is the client's own. Both the previous version of this
     * method and {@code remoteAddress()} under {@code proxy-address-forwarding}
     * take exactly that one — Quarkus's {@code ForwardedParser} calls
     * {@code getFirstElement(forHeader)} — so one forged header per attempt
     * bought a fresh counter. {@code quarkus.http.proxy.trusted-proxies} does
     * not help: it decides whether the header is read at all, never which
     * element is kept.</p>
     */
    private String address(RoutingContext contexte) {
        // connection(), not request(): proxy-address-forwarding has already
        // rewritten remoteAddress() with the client's own forged value. Only the
        // TCP peer says who is really speaking.
        String pair = hostOf(contexte.request().connection().remoteAddress());
        TrustedProxies fiables = proxysFiables;
        if (!fiables.contains(pair)) {
            return pair;
        }
        List<String> transmises = forwardedFor(contexte);
        for (int i = transmises.size() - 1; i >= 0; i--) {
            String candidat = transmises.get(i);
            if (!fiables.contains(candidat)) {
                return candidat;
            }
        }
        return pair;
    }

    /** {@code X-Forwarded-For} split into its entries, empty when absent. */
    private static List<String> forwardedFor(RoutingContext contexte) {
        String entete = contexte.request().getHeader("X-Forwarded-For");
        if (entete == null || entete.isBlank()) {
            return List.of();
        }
        return Arrays.stream(entete.split(","))
                .map(String::trim)
                .filter(valeur -> !valeur.isEmpty())
                .toList();
    }

    /**
     * Vert.x renders a non-IP host as a {@code null} {@code hostAddress()} — a
     * proxy emitting {@code unknown}, which Squid does, used to reach the
     * counter map as a null key and answer 500 on {@code /j_security_check}.
     */
    private static String hostOf(SocketAddress adresse) {
        if (adresse == null) {
            return "inconnue";
        }
        String ip = adresse.hostAddress();
        if (ip != null && !ip.isBlank()) {
            return ip;
        }
        String hote = adresse.host();
        return hote == null || hote.isBlank() ? "inconnue" : hote;
    }
}
