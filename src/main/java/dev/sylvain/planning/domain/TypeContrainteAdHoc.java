package dev.sylvain.planning.domain;

/**
 * What a {@link ContrainteAdHoc} asks of the solver. The incompatibility is
 * enforced as a hard constraint, at the same level as the legal ones; the
 * other two are soft and never more (see {@code AdHocConstraints}). The forced
 * assignment and the forced unavailability are gone (ADR 0117): a lock and a
 * day off of the fiche say the same thing.
 */
public enum TypeContrainteAdHoc {
    /** The two animateurs named may not work on the same timeslot, whatever the stand. */
    INCOMPATIBILITE,
    /** A pair to bring together: rewarded on the same stand and timeslot, never imposed. */
    AFFINITE,
    /**
     * Two to four animateurs who arrive and leave together — a car shared, a
     * common starting point. Soft: the same days worked, the first start and
     * the last end of those days aligned within a tolerance. No timeslot, no
     * stand: the members may hold different stands on different emplacements.
     */
    ARRIVEE_GROUPEE
}
