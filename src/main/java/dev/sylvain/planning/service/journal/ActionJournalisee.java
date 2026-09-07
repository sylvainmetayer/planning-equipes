package dev.sylvain.planning.service.journal;

/**
 * One action the application knows how to describe, as the history screen
 * reads it.
 *
 * @param code    stable identifier, stored in {@code journal_action.action};
 *                never translated, never renamed once written
 * @param libelle what it says in French on screen — a verb and its object,
 *                written for an organiser and not for a developer
 * @param entite  what kind of thing it acts on, {@code null} when it acts on
 *                the edition as a whole (a solve, a full export)
 */
public record ActionJournalisee(String code, String libelle, Entite entite) {

    /** What an action can bear upon. Names the referential family, not the table. */
    public enum Entite {
        ANIMATEUR,
        STAND,
        CRENEAU,
        EMPLACEMENT,
        TYPOLOGIE,
        AJUSTEMENT,
        VERROUILLAGE,
        EDITION,
        PLANNING,
        PARAMETRES,
        ECHANGE,
        DISPONIBILITE,
        INSTANTANE,
        SAUVEGARDE
    }
}
