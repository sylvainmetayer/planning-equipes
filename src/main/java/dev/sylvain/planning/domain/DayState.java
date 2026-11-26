package dev.sylvain.planning.domain;

/**
 * Where a day of the plan stands (ADR 0121): to be read, read, frozen. One
 * value per day, derived from the two rows that already said it — never
 * stored on its own:
 *
 * <ul>
 *   <li>{@link #FIGEE} — the day carries a {@link TypeVerrouillage#JOUR} lock,
 *       whatever else it carries: the lock <em>is</em> the frozen state, so
 *       the solver, the plan section of a file and the Verrouillages screen
 *       read it as they always did;</li>
 *   <li>{@link #RELUE} — a {@link ValidationJournee} reading and no such lock:
 *       nothing is frozen, and a solve that recomputes the day withdraws it
 *       (ADR 0069);</li>
 *   <li>{@link #A_RELIRE} — neither.</li>
 * </ul>
 */
public enum DayState {
    A_RELIRE,
    RELUE,
    FIGEE;

    /** The rule, on the two facts it reads: a lock wins over a reading. */
    public static DayState of(boolean read, boolean locked) {
        if (locked) {
            return FIGEE;
        }
        return read ? RELUE : A_RELIRE;
    }
}
