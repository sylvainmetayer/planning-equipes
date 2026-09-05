package dev.sylvain.planning.service;

/** The cross-field inconsistencies a write reports without refusing it. */
public enum TypeAvertissement {

    /**
     * An off day of an animateur falls outside the event's span — see
     * {@link JoursEvenement}. It will never meet a créneau, so it protects
     * nobody: almost always a month or a year slip.
     */
    INDISPONIBILITE_HORS_EVENEMENT,

    /**
     * An off day inside the event's span but on a date carrying no créneau. It
     * is not a slip — the day really is in the middle of the event — yet it is
     * doomed all the same: the declaration form only offers the dates of the
     * créneaux, and applying a declaration replaces {@code joursIndisponibles}
     * wholesale, so the day disappears. Same fact the CSV import refuses a row
     * over; the manual form warns instead of refusing, per the doctrine of
     * {@link Avertissement}.
     */
    INDISPONIBILITE_JOUR_SANS_CRENEAU,

    /**
     * The birth date makes the animateur a minor on at least one day of the
     * event. Never stored as a flag: derived from
     * {@code Animateur.isMineurOn} at each day of the span, exactly as the
     * solver does.
     */
    MINEUR_PENDANT_EVENEMENT,

    /** No stand is open at any point of the timeslot: it will open no seat at all. */
    CRENEAU_HORS_OUVERTURE_STANDS,

    /**
     * The timeslot starts before every stand opens, or ends after they all
     * close. The middle of the slot is not looked at: a lunch closure common
     * to every stand is a schedule, not a mistake.
     */
    CRENEAU_DEBORDE_OUVERTURE_STANDS,

    /**
     * A dated exception of a stand falls outside the event's span: it names a
     * day no créneau will ever read — almost always a month or a year slip,
     * the stand-side twin of {@link #INDISPONIBILITE_HORS_EVENEMENT}.
     */
    STAND_EXCEPTION_HORS_EVENEMENT,

    /**
     * A window of the stand — from a rule or a dated exception — overlaps no
     * créneau of its day: it validates, it is written, and it changes
     * nothing. The classic "14 h-16 h on a day closing at noon", read on the
     * resolved windows so a rule expanding onto such a day is caught too.
     */
    STAND_FENETRE_SANS_EFFET,

    /**
     * After this write the stand is open on no créneau at all: it will
     * generate no seat and nobody will ever be scheduled on it.
     */
    STAND_JAMAIS_OUVERT
}
