package dev.sylvain.planning.domain;

/**
 * Lifecycle of a {@link DemandeEchange} (issue #165). A demande only ever
 * moves forward: EN_ATTENTE_CIBLE then PROPOSEE are the two non-terminal
 * states — the targeted colleague agrees first, so the admin never has to ask
 * both sides whether they are OK with the swap — and nothing is applied to
 * the planning before an admin explicitly accepts.
 */
public enum StatutDemandeEchange {

    /** Submitted by the demandeur, waiting for the targeted colleague's agreement. */
    EN_ATTENTE_CIBLE,

    /** Agreed by the colleague, waiting for an admin decision. */
    PROPOSEE,

    /** Accepted by an admin: the swap has been applied and pinned. */
    ACCEPTEE,

    /** Refused by an admin: the planning is untouched. */
    REFUSEE,

    /** Declined by the targeted colleague: the admin never has to arbitrate. */
    REFUSEE_CIBLE,

    /** Withdrawn by the demandeur before any decision. */
    ANNULEE
}
