package dev.sylvain.planning.domain;

/**
 * Which of the two mutually exclusive per-day modes a {@link HoraireStand}
 * states — the rule-level counterpart of the {@link OuvertureStand} /
 * {@link IndisponibiliteStand} pair.
 */
public enum ModeHoraire {

    /**
     * The listed windows are when the stand <b>is</b> open; it is closed the
     * rest of the day. Expands to {@link OuvertureStand} rows.
     */
    OUVERTURE,

    /**
     * The listed windows are when the stand is <b>closed</b>; it is open the
     * rest of the day. Expands to {@link IndisponibiliteStand} rows.
     */
    FERMETURE
}
