package dev.sylvain.planning.service.journal;

/**
 * Who did something. Four kinds, and no more precision than the application
 * actually holds: there is no user table here, a single admin account signs
 * in, one shared key opens the MCP transport, and the scheduled jobs answer
 * to nobody.
 *
 * <p>Claiming more would be worse than claiming this: a journal that named an
 * operator the application cannot tell apart from another would be believed.</p>
 */
public enum Acteur {

    /** The admin session — the form login, or an address a trusted proxy asserted. */
    ADMIN,
    /** An animateur acting from their espace; {@code acteurId} is their id, never their name. */
    ANIMATEUR,
    /**
     * A caller that presented no valid credential. The espace routes are open
     * — the URL token <i>is</i> the credential — so a bad or expired one gets
     * as far as being refused, and that refusal is worth a line. Filing it
     * under {@link #ADMIN} would put somebody probing espace links under
     * « Administration », precisely on the rows an operator goes looking for.
     */
    ANONYME,
    /** A tool call over MCP: an assistant, driven by whoever holds the API key. */
    ASSISTANT,
    /** The application itself: the nightly sends, the backup, a queue replayed at startup. */
    SYSTEME
}
