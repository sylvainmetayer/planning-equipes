package dev.sylvain.planning.domain;

/**
 * Which of the two mutually exclusive per-day modes a {@link PlageStand}
 * states.
 */
public enum ModeHoraire {

    /** The window is when the stand <b>is</b> open; it is closed the rest of the day. */
    OUVERTURE,

    /** The window is when the stand is <b>closed</b>; it is open the rest of the day. */
    FERMETURE
}
