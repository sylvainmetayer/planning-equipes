package dev.sylvain.planning.domain;

/**
 * Which days a {@link HoraireStand} applies to. The declaration order is also
 * the <b>specificity</b> order used to arbitrate between two rules covering the
 * same day: the most specific one wins, and only its windows are honoured (see
 * {@link HoraireStand#specificite()} and
 * {@code HoraireStandResolver}).
 */
public enum TypeJoursHoraire {

    /** Every day of the event — the common case for a stable opening pattern. */
    TOUS,

    /** Only the listed days of the week, e.g. "the weekend opens at 10:00". */
    JOURS_SEMAINE,

    /** Every day of a contiguous date range, bounds included. */
    PLAGE,

    /** Only the explicitly listed dates. */
    DATES;

    /**
     * How specific this selector is, {@code 0} being the least: the ordinal,
     * which the declaration order above keeps meaningful.
     */
    public int specificite() {
        return ordinal();
    }
}
