package dev.sylvain.planning.service;

import java.time.Instant;

/**
 * A request the domain refuses, as opposed to a bug. The distinction matters
 * because the two deserve opposite treatments: a refusal is the expected
 * answer to a bad request and gets a 4xx with its message shown as-is; a bug
 * gets a 500 and a Sentry alert.
 *
 * <p>That distinction used to live in the resources, as ~30 hand-written
 * {@code try/catch (IllegalArgumentException) → Response.status(...)} blocks.
 * Forgetting one on a new endpoint meant a 500 and an alert for a mistyped
 * form field — and it had already happened. Worse, the status was a property
 * of the <b>endpoint</b> rather than of the error: the same
 * {@code IllegalArgumentException} became a 400 in one resource and a 404 in
 * another, so the answer to "what does an unknown id return?" depended on
 * which method you happened to call.</p>
 *
 * <p>The status now travels with the error. {@code BusinessErrorMapper}
 * switches over this sealed hierarchy exhaustively, so a new kind of refusal
 * does not compile until someone says what it answers.</p>
 *
 * <p>MCP reads the same distinction through
 * {@code mcp/RefusMetierInterceptor}: a refusal comes back as a tool result in
 * error carrying this message, anything else as « Internal error » (issue
 * #529). The message therefore leaves the building, and on that side it may
 * not name anybody: designate an animateur by id, never by nom/prénom
 * (issue #107, held by {@code McpRefusMetierStructurelleTest}).</p>
 *
 * <p>It extends {@link IllegalArgumentException} deliberately: that is what
 * these already were, so every existing {@code catch} and every test
 * assertion keeps holding, and a plain {@code IllegalArgumentException} — one
 * nobody meant to throw — still falls through to the 500 it deserves.</p>
 */
public abstract sealed class BusinessError extends IllegalArgumentException {

    private BusinessError(String message) {
        super(message);
    }

    private BusinessError(String message, Throwable cause) {
        super(message, cause);
    }

    /** The request is malformed or breaks a business rule — {@code 400}. */
    public static final class Invalid extends BusinessError {

        public Invalid(String message) {
            super(message);
        }

        public Invalid(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** The request names something that does not exist here — {@code 404}. */
    public static final class NotFound extends BusinessError {

        public NotFound(String message) {
            super(message);
        }
    }

    /** The request is well formed but the current state refuses it — {@code 409}. */
    public static final class Conflict extends BusinessError {

        public Conflict(String message) {
            super(message);
        }
    }

    /**
     * The write was based on a read that is out of date (issue #362): another
     * session wrote the row since the caller loaded it — {@code 409} too, but
     * told apart from {@link Conflict} because the client has a specific
     * answer to it, reload or overwrite, that no other conflict offers. Carries
     * the row's current {@code modifieLe} so the client can also overwrite
     * knowingly by sending it back.
     */
    public static final class Stale extends BusinessError {
        private final transient Instant modifieLe;

        public Stale(String message, Instant modifieLe) {
            super(message);
            this.modifieLe = modifieLe;
        }

        public Instant getModifieLe() {
            return modifieLe;
        }
    }
}
