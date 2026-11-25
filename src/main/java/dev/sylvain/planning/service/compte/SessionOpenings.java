package dev.sylvain.planning.service.compte;

import jakarta.enterprise.context.ApplicationScoped;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * When each form session was <b>opened</b>, as the login itself saw it
 * (ADR 0097).
 *
 * <p>The form session cookie of Quarkus is renewed while it is used and carries
 * no creation time, so "how long ago did this person sign in" cannot be read
 * from it. The login filter notes the value of the cookie it just set, here, at
 * that instant; the recent sign-in control looks the request's cookie up. Only
 * the SHA-256 of the cookie is kept, in memory, for a quarter of an hour.
 * Quarkus reissues the cookie once it is a minute old: the login filter notes
 * the new value with the same instant ({@link #renew}), so a session keeps its
 * opening instant. A restart forgets everything, which asks
 * everybody to sign in again before a sensitive gesture, and nothing more.</p>
 */
@ApplicationScoped
public class SessionOpenings {

    private static final Duration RETENTION = Duration.ofMinutes(15);

    private static final int MAX_ENTRIES = 2_000;

    private final Map<String, Instant> openedAt = new ConcurrentHashMap<>();

    /** Notes that the session whose cookie value is {@code cookieValue} was opened at {@code at}. */
    public void record(String cookieValue, Instant at) {
        if (cookieValue == null || cookieValue.isEmpty()) {
            return;
        }
        if (openedAt.size() >= MAX_ENTRIES) {
            Instant limit = Instant.now().minus(RETENTION);
            openedAt.values().removeIf(opened -> opened.isBefore(limit));
        }
        // Still full (logins in a burst, from accounts anyone can hold): the oldest goes, so
        // that a burst cannot keep the next administrator's login from being noted.
        while (openedAt.size() >= MAX_ENTRIES) {
            openedAt.entrySet().stream()
                    .min(Map.Entry.comparingByValue())
                    .ifPresent(oldest -> openedAt.remove(oldest.getKey(), oldest.getValue()));
        }
        openedAt.put(fingerprint(cookieValue), at);
    }

    /**
     * The session whose cookie was {@code previous} now travels as {@code renewed}:
     * it keeps its opening. The previous value stays until it ages out — two
     * requests sent in parallel with it can each be answered a renewed cookie.
     */
    public void renew(String previous, String renewed) {
        openedAt(previous).ifPresent(at -> record(renewed, at));
    }

    /** The instant that session opened, empty when unknown (forgotten, never seen). */
    public Optional<Instant> openedAt(String cookieValue) {
        return cookieValue == null || cookieValue.isEmpty()
                ? Optional.empty()
                : Optional.ofNullable(openedAt.get(fingerprint(cookieValue)));
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
