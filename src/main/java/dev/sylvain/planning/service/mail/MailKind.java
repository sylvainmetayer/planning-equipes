package dev.sylvain.planning.service.mail;

/**
 * Which mail to an animateur a line of {@code envoi_mail} records. Stored by
 * name: renaming a constant orphans the rows already written.
 */
public enum MailKind {

    /** The planning just published, with what changed for them. */
    PLANNING_PUBLIE,

    /** Their individual planning, sent by hand from the planning page. */
    PLANNING_INDIVIDUEL,

    /** The espace access code. */
    CODE_ACCES,

    /** The invitation to declare their availability. */
    INVITATION_DECLARATION,

    /** « Relancer maintenant », from the Animateurs page. */
    RELANCE_MANUELLE,

    /** The day-before reminder, sent at night. */
    RAPPEL_VEILLE,

    /** The reminder of the unconfirmed, sent at night. */
    RELANCE_NUIT
}
