package dev.sylvain.planning.domain;

/**
 * What a {@link VerrouillagePlanning} freezes. Each value names the single
 * target column the lock carries (see the {@code verrouillage_planning}
 * table's check constraint).
 */
public enum TypeVerrouillage {

    /** Every seat held by one animateur, over the whole festival. */
    ANIMATEUR,

    /** Every seat of one stand, on all of its créneaux. */
    STAND,

    /** Every seat of every stand on one calendar day. */
    JOUR,

    /** Every seat of one single créneau. */
    CRENEAU
}
