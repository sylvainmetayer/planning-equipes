package dev.sylvain.planning.domain;

/**
 * Lifecycle of a {@link DemandeCoequipier}: validated or not, nothing else —
 * a demand an admin refuses or cancels is deleted. A wish, never applied with the
 * declaration that carried it: only an admin turns it into an exception.
 */
public enum StatutDemandeCoequipier {

    /** Declared from the espace, waiting for an admin. */
    EN_ATTENTE,

    /** Turned into an ad hoc exception by an admin. */
    VALIDEE
}
