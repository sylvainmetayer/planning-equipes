package dev.sylvain.planning.domain;

/**
 * Why an animateur names teammates from their espace. One table holds every
 * kind, so the picker and the admin's validation are written once.
 */
public enum NatureCoequipier {
    /** « Je viens avec… »: arriving and leaving together, whatever the stands. */
    COVOITURAGE,
    /** A pair wished on the same stand. Reserved: nothing writes it yet. */
    BINOME
}
