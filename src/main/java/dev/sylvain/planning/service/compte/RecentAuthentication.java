package dev.sylvain.planning.service.compte;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * Whether the caller signed in <b>recently</b> (ADR 0097): the control that
 * stands before a gesture a stolen or forgotten open session must not be able
 * to make — generating or revealing the MCP key — and replaces the retyping of
 * the configuration's password, which an administrator signed in through the
 * provider or a local account does not have.
 *
 * <p>The instant of authentication is read where it is reliable: the
 * {@code auth_time} claim of the provider's ID token for an OIDC session, the
 * signed cookie of the login itself ({@link SessionOpenings}, noted by the login itself) for a form
 * session, break-glass or local. Anything else — no claim, no valid cookie —
 * is not recent.</p>
 */
@ApplicationScoped
public class RecentAuthentication {

    /** How the caller must sign in again; the screen offers the matching way. */
    public enum Mode {
        OIDC,
        LOCAL,
        BREAK_GLASS
    }

    private final SessionOpenings openings;

    private final Duration window;

    @Inject
    public RecentAuthentication(
            SessionOpenings openings, @ConfigProperty(name = "planning.auth.recent-window") Duration window) {
        this.openings = openings;
        this.window = window;
    }

    /**
     * @param cookieValue the form session cookie of the request, or {@code null}
     * @return empty when the caller authenticated within the window, else how to sign in again
     */
    public Optional<Mode> reauthenticationRequired(SecurityIdentity identity, String cookieValue) {
        Optional<Instant> openedAt = identity != null && identity.getPrincipal() instanceof JsonWebToken token
                ? authTime(token)
                : openings.openedAt(cookieValue);
        Instant now = Instant.now();
        if (openedAt.filter(at -> !at.isAfter(now.plusSeconds(60)) && at.isAfter(now.minus(window)))
                .isPresent()) {
            return Optional.empty();
        }
        return Optional.of(mode(identity));
    }

    private static Optional<Instant> authTime(JsonWebToken token) {
        Object claim = token.getClaim("auth_time");
        return claim instanceof Number seconds
                ? Optional.of(Instant.ofEpochSecond(seconds.longValue()))
                : Optional.empty();
    }

    private static Mode mode(SecurityIdentity identity) {
        if (identity == null || identity.isAnonymous()) {
            return Mode.BREAK_GLASS;
        }
        if (identity.getPrincipal() instanceof JsonWebToken) {
            return Mode.OIDC;
        }
        // Read from the attribute the local sign-in sets, never from the principal's name (an address).
        return Boolean.TRUE.equals(identity.getAttribute(Compte.LOCAL_ATTRIBUTE)) ? Mode.LOCAL : Mode.BREAK_GLASS;
    }
}
