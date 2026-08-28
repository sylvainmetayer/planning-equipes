package dev.sylvain.planning.domain;

/**
 * Lifecycle of a {@link DeclarationDisponibilite} (issue #291). A declaration
 * is submitted from the espace animateur and waits: nothing it says reaches the
 * referential before an admin applies it, and the decision is all-or-nothing —
 * the admin never picks a day or a wish out of the proposal.
 *
 * <p>Only one declaration per animateur is ever {@link #EN_ATTENTE}: submitting
 * again replaces the pending one rather than queuing behind it, so two
 * contradictory versions of the same person never reach the admin's desk.</p>
 */
public enum StatutDeclaration {

    /** Submitted from the espace, waiting for an admin decision. */
    EN_ATTENTE,

    /** Applied by an admin: the animateur's fiche now says what they declared. */
    APPLIQUEE,

    /** Refused by an admin: the referential is untouched. */
    REFUSEE
}
