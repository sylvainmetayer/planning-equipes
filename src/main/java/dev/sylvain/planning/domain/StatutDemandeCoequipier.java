package dev.sylvain.planning.domain;

/**
 * Lifecycle of a {@link DemandeCoequipier}. A wish, never applied with the
 * declaration that carried it: only an admin turns it into an exception.
 */
public enum StatutDemandeCoequipier {

    /** Declared from the espace, waiting for an admin. */
    EN_ATTENTE,

    /** Turned into an ad hoc exception by an admin. */
    VALIDEE,

    /** Set aside by an admin: nothing was written. */
    ECARTEE,

    /**
     * Validated, then cancelled by an admin: the exception it created or
     * joined was deleted, the demand stays as the trace of both decisions.
     */
    ANNULEE
}
