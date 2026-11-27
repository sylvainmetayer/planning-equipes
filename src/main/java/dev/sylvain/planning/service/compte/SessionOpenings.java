package dev.sylvain.planning.service.compte;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The form sessions this instance has seen: when each was <b>opened</b>, as the
 * login itself saw it (ADR 0097), and whether it was <b>logged out</b>.
 *
 * <p>The form session cookie of Quarkus is encrypted, stateless, renewed while
 * it is used and carries no creation time, so neither "how long ago did this
 * person sign in" nor "did this person log out" can be read from it. The login
 * filter notes the value of the cookie it just set, here, at that instant; the
 * recent sign-in control looks the request's cookie up. Quarkus reissues the
 * cookie as it is used ({@code new-cookie-interval}): the login filter notes the new value as the
 * same session ({@link #renew}), so a session keeps its opening instant — and a
 * logout reaches every value the session ever had, a copy taken an hour before
 * included. Only the SHA-256 of each value is kept, in memory, for as long as
 * that value opens a session (the form timeout).</p>
 *
 * <p>A restart forgets everything: it asks everybody to sign in again before a
 * sensitive gesture, a cookie logged out before it opens again if the
 * encryption key is kept, and a session opened before it cannot be logged out
 * on the server. shortcut: revocation lives in memory and is
 * forgotten on restart; persist it if a restart within the form timeout of a
 * stolen cookie becomes a threat worth a table.</p>
 */
@ApplicationScoped
public class SessionOpenings {

    /**
     * About 5 MB at the ceiling. A session adds a value at each renewal
     * ({@code quarkus.http.auth.form.new-cookie-interval}, 5 min): about a hundred over 8 h of use.
     */
    static final int MAX_ENTRIES = 20_000;

    /** One session, shared by every value its cookie took. */
    private static final class Session {
        private final Instant openedAt;
        private volatile boolean loggedOut;

        Session(Instant openedAt) {
            this.openedAt = openedAt;
        }
    }

    /** {@code order} breaks the ties of {@code recordedAt}: the clock can return one instant twice. */
    private record Entry(Session session, Instant recordedAt, long order) {}

    /**
     * Unrevoked values go first, the longest-recorded first: the value a session travels with now is
     * its newest, so a burst takes old copies before it breaks a session's chain.
     */
    private static final Comparator<Map.Entry<String, Entry>> EVICTION = Comparator.comparing(
                    (Map.Entry<String, Entry> e) -> e.getValue().session().loggedOut)
            .thenComparingLong(e -> e.getValue().order());

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    private final AtomicLong recorded = new AtomicLong();

    /** How long a cookie value opens a session once issued: {@code quarkus.http.auth.form.timeout}. */
    private final Duration retention;

    /** For the unit tests: the form timeout of {@code application.properties}. */
    public SessionOpenings() {
        this(Duration.ofHours(8));
    }

    @Inject
    public SessionOpenings(@ConfigProperty(name = "quarkus.http.auth.form.timeout") Duration retention) {
        this.retention = retention;
    }

    /** Notes that the session whose cookie value is {@code cookieValue} was opened at {@code at}. */
    public void record(String cookieValue, Instant at) {
        if (cookieValue != null && !cookieValue.isEmpty()) {
            put(cookieValue, new Session(at));
        }
    }

    /**
     * The session whose cookie was {@code previous} now travels as {@code renewed}:
     * it keeps its opening, and a logout of either reaches both. The previous
     * value stays until it ages out — two requests sent in parallel with it can
     * each be answered a renewed cookie.
     */
    public void renew(String previous, String renewed) {
        if (previous == null || renewed == null || renewed.isEmpty()) {
            return;
        }
        Entry known = entries.get(fingerprint(previous));
        if (known != null) {
            put(renewed, known.session());
        }
    }

    /** The instant that session opened, empty when unknown (forgotten, never seen). */
    public Optional<Instant> openedAt(String cookieValue) {
        return entry(cookieValue).map(e -> e.session().openedAt);
    }

    /**
     * Logs out the session {@code cookieValue} belongs to: every value it took,
     * before and after this one, stops opening it. A value this instance never
     * noted is left alone — the route is public, and junk values would crowd
     * out the live sessions.
     */
    public void logOut(String cookieValue) {
        entry(cookieValue).ifPresent(e -> e.session().loggedOut = true);
    }

    /** Whether {@code cookieValue} belongs to a session that was logged out. */
    public boolean loggedOut(String cookieValue) {
        return entry(cookieValue).map(e -> e.session().loggedOut).orElse(false);
    }

    private Optional<Entry> entry(String cookieValue) {
        return cookieValue == null || cookieValue.isEmpty()
                ? Optional.empty()
                : Optional.ofNullable(entries.get(fingerprint(cookieValue)));
    }

    private void put(String cookieValue, Session session) {
        Instant now = Instant.now();
        if (entries.size() >= MAX_ENTRIES) {
            Instant limit = now.minus(retention);
            entries.values().removeIf(e -> e.recordedAt().isBefore(limit));
        }
        // Still full (logins in a burst, from accounts anyone can hold): the oldest goes, so that a
        // burst can neither keep the next administrator's login from being noted nor bring a
        // logged-out session back.
        while (entries.size() >= MAX_ENTRIES) {
            entries.entrySet().stream()
                    .min(EVICTION)
                    .ifPresent(oldest -> entries.remove(oldest.getKey(), oldest.getValue()));
        }
        entries.put(fingerprint(cookieValue), new Entry(session, now, recorded.incrementAndGet()));
    }

    private static String fingerprint(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
