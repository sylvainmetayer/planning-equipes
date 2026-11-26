package dev.sylvain.planning.domain;

/**
 * Which end of a meal window the break is preferred at, an organisation
 * setting of the edition ({@link ParametresQualite#placementRepas()}).
 *
 * <p>It tunes the soft term {@code coupureRepasPlacementPrefere}, never the
 * hard rule: whichever value, the break is owed and must fit inside the window.
 * The term stays soft, weight 1, whatever the level the edition gives
 * {@code coupureRepasObligatoire} (ADR 0123).</p>
 */
public enum PlacementRepas {
    /** The shipped placement: late at midday (the stands have just opened), early in the evening. */
    FIN_MIDI_DEBUT_SOIR,
    /** Every window: the break as early as the window allows. */
    DEBUT,
    /** Every window: the break as late as the window allows. */
    FIN,
    /** No preference: the term is silent. */
    AUCUN
}
