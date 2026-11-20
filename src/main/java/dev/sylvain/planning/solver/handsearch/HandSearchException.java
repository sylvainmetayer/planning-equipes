package dev.sylvain.planning.solver.handsearch;

/**
 * The engine gives up rather than hand back a score computed wrong. Two cases
 * a caller words differently:
 *
 * <ul>
 *   <li>a <b>refusal</b> — the problem has a shape the engine does not score,
 *       or a plan left the range it can order: nothing is wrong with the
 *       engine, the other search solves this problem;</li>
 *   <li>a <b>defect</b> ({@link #isDefect()}) — the engine caught itself in
 *       contradiction, its running total against the score read from scratch:
 *       a bug in this package, to report as one.</li>
 * </ul>
 *
 * <p>The message is a French clause, completed by the caller into the
 * sentence the job shows.</p>
 */
public final class HandSearchException extends RuntimeException {

    private final boolean defect;

    private HandSearchException(String message, boolean defect) {
        super(message);
        this.defect = defect;
    }

    static HandSearchException refused(String message) {
        return new HandSearchException(message, false);
    }

    static HandSearchException defect(String message) {
        return new HandSearchException(message, true);
    }

    /** Whether the engine contradicted itself, as opposed to declining a problem. */
    public boolean isDefect() {
        return defect;
    }
}
