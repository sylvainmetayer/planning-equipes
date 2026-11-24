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
 */
public record Compte(
        String id,
        String email,
        String nom,
        Instant creeLe,
        Instant derniereConnexionLe,
        Instant desactiveLe,
        List<Habilitation> habilitations) {

    /** The id the journal gives the implicit account of the configuration. */
    public static final String CONFIG_ID = "admin";

    /** The identity attribute carrying the account of a session, once a sign-in binds one. */
    public static final String IDENTITY_ATTRIBUTE = "planning.compte";

    public boolean actif() {
        return desactiveLe == null;
    }
}
