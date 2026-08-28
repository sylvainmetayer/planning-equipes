package dev.sylvain.planning.domain;

/**
 * Where an animateur stands with the planning that was published to them
 * (issue #293).
 *
 * <p>{@link #NON_VU} is the state everybody starts in and the one a
 * republication sends the concerned people back to. It is deliberately
 * <b>not</b> stored: no row in {@code confirmation_planning} means NON_VU, so
 * resetting is a delete and there is never a second way of writing "this
 * person has not answered".</p>
 */
public enum StatutConfirmation {

    /** Nothing came back — either nobody was asked yet, or the plan moved since. */
    NON_VU,

    /** « J'ai lu et je serai là », clicked in the espace, with the date it happened. */
    CONFIRME,

    /**
     * The automatic reminder (issue #299) went out and is still unanswered.
     * Kept apart from {@link #NON_VU} so the job does not write to the same
     * person every night — the state itself is what stops the loop.
     */
    RELANCE
}
