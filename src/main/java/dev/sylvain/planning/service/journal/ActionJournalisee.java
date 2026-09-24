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
 * @param changesData whether it changes what a solve would be given —
 *                the referentials, the ad hoc adjustments, the locks, the
 *                parameters and the rules. Those are the actions that make an
 *                already-persisted plan out of date, and the ones the solver
 *                screen lists under « des données ont été modifiées » ; a
 *                send, an export, a snapshot or a solve does not qualify.
 * @param export  whether it is a file leaving the application — an export
 *                from the administration or a download from an espace. The
 *                history's « Exports » filter reads this flag, server-side,
 *                rather than guessing from the code's spelling.
 */
public record ActionJournalisee(String code, String libelle, Entite entite, boolean changesData, boolean export) {

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
