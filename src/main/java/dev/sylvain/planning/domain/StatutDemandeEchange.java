package dev.sylvain.planning.domain;

/**
 * Lifecycle of a {@link DemandeEchange} (issue #165). A demande only ever
 * moves forward: PROPOSEE is the single non-terminal state, and nothing is
 * applied to the planning before an admin explicitly accepts.
 */
public enum StatutDemandeEchange {

    /** Submitted by the animateur, waiting for an admin decision. */
    PROPOSEE,

    /** Accepted by an admin: the swap has been applied and pinned. */
    ACCEPTEE,

    /** Refused by an admin: the planning is untouched. */
    REFUSEE,

    /** Withdrawn by the demandeur before any decision. */
    ANNULEE
}
