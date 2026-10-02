package dev.sylvain.planning.api;

import dev.sylvain.planning.config.ConfigAdminLogin;
import dev.sylvain.planning.config.TrustedProxies;
import dev.sylvain.planning.service.FailureLockout;
import dev.sylvain.planning.service.journal.LoginJournalEntry;
import dev.sylvain.planning.service.journal.LoginJournalEntry.Evenement;
import dev.sylvain.planning.service.journal.LoginJournalService;
import io.quarkus.security.spi.runtime.AuthenticationFailureEvent;
import io.quarkus.vertx.http.runtime.filters.Filters;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

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
 *
 * <h2>The trace it leaves</h2>
 *
 * <p>Seeing each outcome, it is also what writes the admin login journal
 * ({@link LoginJournalService}, ADR 0076): a successful login, every failure,
 * and the lockout — once, on the failure that reaches the ceiling. A request
 * refused while locked writes nothing: it tried no credential, and one line
 * per refusal would hand the table to whoever hammers the form. The time and
 * the address go in, never the password nor the username typed. The write
 * leaves the event loop for a worker, and its failure costs nothing but the
 * line. Under an attack from many addresses, {@link LoginJournalThrottle}
 * bounds what reaches the table and the worker pool.</p>
 */
@ApplicationScoped
public class AdminLoginLimiter {

    private static final Logger LOG = Logger.getLogger(AdminLoginLimiter.class);

    /** Target of the Quarkus form login ({@code quarkus.http.auth.form.post-location} by default). */
    static final String CHEMIN_CONNEXION = "/j_security_check";

    /** After the security headers, before anything handles the request. */
    private static final int PRIORITE = 250;

    private final ConfigAdminLogin config;

    private final LoginJournalService journal;

    /** Read from the configuration so the two can never drift apart. */
    private final String nomCookieSession;

    @Inject
    public AdminLoginLimiter(
            ConfigAdminLogin config,
            LoginJournalService journal,
            @ConfigProperty(name = "quarkus.http.auth.form.cookie-name") String nomCookieSession) {
        this.config = config;
        this.journal = journal;
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
     * Failed attempts journalled per hour, instance-wide. One address alone
     * stays far below: locked after {@code CONNEXION_MAX_ECHECS} failures for
     * {@code CONNEXION_DUREE_BLOCAGE}, it fails twenty times an hour with the
     * defaults; only many addresses at once reach it.
     */
    private static final int FAILURES_PER_HOUR = 1_000;

    /** Journal writes handed to the worker pool and not over yet. */
    private static final int MAX_PENDING_WRITES = 64;

    private final LoginJournalThrottle throttle =
            new LoginJournalThrottle(FAILURES_PER_HOUR, Duration.ofHours(1), MAX_PENDING_WRITES);

    /**
     * The declared proxies, parsed once. Built here rather than lazily so a
     * malformed entry fails the boot: skipping it would leave the deployment
     * believing it had declared its proxy, while the lock quietly counted every
     * visitor on the proxy's own single counter.
     */
    private final AtomicReference<TrustedProxies> proxysFiables = new AtomicReference<>(TrustedProxies.NONE);

    public void register(@Observes Filters filtres) {
        proxysFiables.set(TrustedProxies.of(config.proxysFiables().orElse(List.of())));
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
                record(false, List.of(new LoginJournalEntry(0, Instant.now(), Evenement.CONNEXION, address)));
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
        String address = address(routage);
        Instant now = Instant.now();
        int run = failures.recordFailure(address, config.dureeBlocage());
        List<LoginJournalEntry> lines = new ArrayList<>();
        lines.add(new LoginJournalEntry(0, now, Evenement.ECHEC, address));
        // Equal, not at least: the lock engages once, on the failure that
        // reaches the ceiling — a parallel straggler past it adds no lockout.
        if (run == config.maxEchecs()) {
            lines.add(new LoginJournalEntry(0, now, Evenement.VERROUILLAGE, address));
        }
        record(true, lines);
    }

    /**
     * Hands the lines to a worker: both callers run on the event loop, where a
     * JDBC write must not block. One task per attempt, so a failure and the
     * lockout it triggered land in that order — or are dropped together, when
     * the throttle says the journal has had its share.
     */
    private void record(boolean failure, List<LoginJournalEntry> lines) {
        if (!throttle.admit(failure, Instant.now())) {
            return;
        }
        try {
            Infrastructure.getDefaultWorkerPool().execute(() -> {
                try {
                    journal.record(lines);
                } finally {
                    throttle.done();
                }
            });
        } catch (RuntimeException e) {
            throttle.done();
            LOG.warnf("An admin login attempt could not be handed to the journal: %s", e);
        }
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
        return ClientAddress.of(contexte, proxysFiables.get());
    }
}
