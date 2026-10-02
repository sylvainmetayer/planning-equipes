package dev.sylvain.planning.service.journal;

import java.time.Instant;

/**
 * One line of the admin login journal.
 *
 * <p><b>Nothing typed goes in</b>: neither the password nor the username — a
 * wrong username is often a password typed in the wrong field. What is kept is
 * when, what came of it, and the address the login lock counts against.</p>
 *
 * @param id        server-side sequence, {@code 0} before it is written
 * @param survenuLe when the attempt was decided
 * @param evenement what came of it
 * @param adresse   the client address, as {@code ClientAddress} reads it
 */
public record LoginJournalEntry(long id, Instant survenuLe, Evenement evenement, String adresse) {

    /** What one attempt on the admin form login came to. */
    public enum Evenement {
        /** The right credentials: a session was opened. */
        CONNEXION,
        /** Wrong credentials. */
        ECHEC,
        /** The failure that reached the ceiling: the address is now refused for a while. */
        VERROUILLAGE
    }
}
