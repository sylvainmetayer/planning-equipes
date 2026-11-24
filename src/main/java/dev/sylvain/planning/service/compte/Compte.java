package dev.sylvain.planning.service.compte;

import java.time.Instant;
import java.util.List;

/**
 * One person able to administer the instance, whatever the edition. Never
 * deleted: {@code desactiveLe} closes the account and keeps its history.
 *
 * <p>The account of the configuration (the single {@code admin} of the
 * environment) is not a row: it is the implicit account {@link #CONFIG_ID},
 * the one an action is attributed to while nobody else is signed in.</p>
 *
 * @param desactivePar the id of the account that deactivated it, never an address
 */
public record Compte(
        String id,
        String email,
        String nom,
        Instant creeLe,
        Instant derniereConnexionLe,
        Instant desactiveLe,
        String desactivePar,
        List<Habilitation> habilitations) {

    /** The id the journal gives the implicit account of the configuration. */
    public static final String CONFIG_ID = "admin";

    /** The id recorded when the application itself withdraws a right (an edition deleted). */
    public static final String SYSTEME_ID = "systeme";

    /** The identity attribute carrying the account of a session, once a sign-in binds one. */
    public static final String IDENTITY_ATTRIBUTE = "planning.compte";
}
