package dev.sylvain.planning.service.compte;

import java.util.Locale;

/**
 * The roles an administrator grants <b>in this application</b>, per edition,
 * with an expiry. Code, not configuration: a configurable permission matrix is
 * 2ⁿ combinations nobody tests, and each role earns its own structural test
 * when its routes open.
 *
 * <p>The enum is meant to grow (a read-only right on one stand, an HR role):
 * a new value comes with its own migration widening the {@code role} check of
 * {@code habilitation}, and with the routes it opens — nothing else reads it.</p>
 */
public enum RoleHabilitation {
    /** The whole instance, like the configuration's account: never scoped to an edition. */
    ADMIN,
    /** An organiser of one edition, or of all of them. */
    ORGANISATEUR;

    /** The name carried by a {@code SecurityIdentity}, the HTTP policies' spelling. */
    public String securityRole() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
