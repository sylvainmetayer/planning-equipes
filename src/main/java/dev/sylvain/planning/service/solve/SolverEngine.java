package dev.sylvain.planning.service.solve;

import java.util.Locale;

/**
 * The search a prepared problem is solved with: {@code planning.solver.engine}
 * (ADR 0087). An instance setting, carried to a worker with the rest of
 * {@link SolverConfiguration.SearchSettings} — the plan a deployment gets must
 * not depend on a variable set, or forgotten, on the machine that computes it.
 */
public enum SolverEngine {

    /** Timefold's local search over the constraint streams: the default. */
    TIMEFOLD,

    /** The search written by hand over an incremental score, refereed by Timefold ({@link HandSearchSolver}). */
    HAND;

    static SolverEngine of(String value) {
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    SolverConfiguration.ENGINE_PROPERTY + " is « " + value + " »: expected timefold or hand", e);
        }
    }

    /** The value as the configuration and a worker's query spell it. */
    String configValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
