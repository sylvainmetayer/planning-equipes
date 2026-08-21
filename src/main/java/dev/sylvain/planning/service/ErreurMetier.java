package dev.sylvain.planning.service;

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
 * <p>The status now travels with the error. {@code ErreurMetierMapper}
 * switches over this sealed hierarchy exhaustively, so a new kind of refusal
 * does not compile until someone says what it answers.</p>
 *
 * <p>It extends {@link IllegalArgumentException} deliberately: that is what
 * these already were, so every existing {@code catch} and every test
 * assertion keeps holding, and a plain {@code IllegalArgumentException} — one
 * nobody meant to throw — still falls through to the 500 it deserves.</p>
 */
public sealed abstract class ErreurMetier extends IllegalArgumentException {

    private ErreurMetier(String message) {
        super(message);
    }

    private ErreurMetier(String message, Throwable cause) {
        super(message, cause);
    }

    /** The request is malformed or breaks a business rule — {@code 400}. */
    public static final class Invalide extends ErreurMetier {

        public Invalide(String message) {
            super(message);
        }

        public Invalide(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** The request names something that does not exist here — {@code 404}. */
    public static final class Introuvable extends ErreurMetier {

        public Introuvable(String message) {
            super(message);
        }
    }

    /** The request is well formed but the current state refuses it — {@code 409}. */
    public static final class Conflit extends ErreurMetier {

        public Conflit(String message) {
            super(message);
        }
    }
}
