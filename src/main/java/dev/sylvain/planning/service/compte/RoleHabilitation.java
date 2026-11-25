package dev.sylvain.planning.service.compte;

/**
 * The roles an administrator grants <b>in this application</b>, per edition,
 * with an expiry. Code, not configuration: a configurable permission matrix is
 * 2ⁿ combinations nobody tests, and each role earns its own structural test
 * when its routes open.
 *
 * <p>The enum is meant to grow (an HR role):
 * a new value comes with its own migration widening the {@code role} check of
 * {@code habilitation}, and with the routes it opens — nothing else reads it.</p>
 */
public enum RoleHabilitation {
    /** The whole instance, like the configuration's account: never scoped to an edition, never expiring. */
    ADMIN,
    /** An organiser of one edition, or of all of them. */
    ORGANISATEUR,
    /**
     * « Lecture de mon stand »: read-only access to the published shifts of the stands the right names, on
     * <b>one</b> edition — and only while it is the active one (ADR 0072). Opens no route of the administration.
     */
    RESPONSABLE_STAND,
    /**
     * « RH »: reads the compliance report of <b>one</b> edition (and the contacts of its animateurs), nothing of
     * the administration. Like the other rights it adds to what the account already holds: an RH who is also an
     * organiser keeps the organiser's reach, and an organiser without it reads none of this.
     */
    RH,
    /**
     * « Lecteur »: sees what an organiser sees in <b>one</b> edition — birth dates and minor status included, the
     * contacts of the RH right excluded — and writes nothing there: the server refuses every write, every
     * computation and every raw-data export (ADR 0099). On another edition where the account is an organiser, the
     * organiser's rights hold as they are.
     */
    LECTEUR
}
