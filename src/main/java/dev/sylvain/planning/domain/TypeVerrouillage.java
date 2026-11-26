package dev.sylvain.planning.domain;

/**
 * What a {@link VerrouillagePlanning} freezes. Three values since 4.0 (ADR
 * 0109): the three grains an operator actually freezes at. Each names the
 * target columns the lock carries (see the {@code verrouillage_planning}
 * table's check constraint).
 */
public enum TypeVerrouillage {

    /**
     * What one animateur holds on one single créneau — nothing else of their
     * planning. Posed when an admin validates a demande d'échange (issue #165):
     * it freezes each swapped seat and forbids handing that animateur another
     * seat on that créneau, while leaving the rest of their event movable. It
     * is also the way to impose an assignment (what the retired forced
     * assignment did).
     */
    ANIMATEUR_CRENEAU,

    /** Every seat of one stand on one calendar day. */
    STAND_JOUR,

    /** Every seat of every stand on one calendar day. */
    JOUR
}
