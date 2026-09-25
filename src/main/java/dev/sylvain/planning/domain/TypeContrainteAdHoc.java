package dev.sylvain.planning.domain;

/**
 * What a {@link ContrainteAdHoc} asks of the solver. The first three are
 * enforced as hard constraints, at the same level as the legal ones; the
 * last two are soft and never more (see {@code AdHocConstraints}).
 */
public enum TypeContrainteAdHoc {
    /** The animateur may hold no seat in the scope — a timeslot, a stand, or the whole event. */
    INDISPONIBILITE_FORCEE,
    /** The two animateurs named may not work on the same timeslot, whatever the stand. */
    INCOMPATIBILITE,
    /** At least one seat of the scope is held by one of the animateurs named — "one of", never "all". */
    AFFECTATION_FORCEE,
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
