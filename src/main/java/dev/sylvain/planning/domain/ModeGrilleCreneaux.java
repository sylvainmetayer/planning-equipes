package dev.sylvain.planning.domain;

/**
 * What the {@link Creneau}s of an edition currently <b>are</b>. Since issue
 * #172 an edition holds exactly one grid, and that grid is read in one of two
 * incompatible ways — which is why nothing can guess the mode from the data
 * alone with certainty, and why the tools that write a grid ask for it.
 *
 * <p>The distinction is not cosmetic: it decides whether an overlap between
 * two créneaux of the same date is a data-entry mistake or the normal product
 * of staggered families, and whether a 10-hour slot is a legitimate opening
 * amplitude or an illegal shift. A validator that does not know the mode can
 * only stay silent on both.</p>
 */
public enum ModeGrilleCreneaux {

    /**
     * Daily opening amplitudes, meant to be sliced into vacations by the
     * découpage ({@code VacationGeneratorService}) before solving. Overlaps
     * within a day are mistakes; long durations are expected.
     */
    AMPLITUDES,

    /**
     * Final vacations, solved as-is: one créneau is one shift somebody will
     * actually work. Overlaps within a day are normal (staggered families);
     * long durations are legal problems.
     */
    VACATIONS
}
