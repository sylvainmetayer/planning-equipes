package dev.sylvain.planning.service.solve;

import dev.sylvain.planning.service.BusinessError;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Turns what a launch asked for into the budget a job actually runs under:
 * the instance's own (ADR 0110), unless a duration is asked for, which the
 * operator's ceiling bounds.
 *
 * <p>Applied on the request thread, when the job is submitted — never when it
 * starts: a job keeps the budget it was launched with.</p>
 *
 * <p>A value <b>asked for now</b> above the ceiling — a {@code ?seconds=}, an
 * MCP {@code secondes} — is refused: silently running two hours when someone
 * asked for three would let them believe the solver had spent its budget.</p>
 */
@ApplicationScoped
public class SolveBudgetPolicy {

    /** An incremental re-solve's own duration when none is asked for: it only refills the seats it re-opened. */
    static final long INCREMENTAL_DEFAULT_SECONDS = 60L;

    private final SolverBudgetBounds bounds;

    @Inject
    public SolveBudgetPolicy(
            @ConfigProperty(name = "planning.solver.seconds-limit", defaultValue = "900") long defaultSecondsLimit,
            @ConfigProperty(name = "planning.solver.unimproved-seconds-limit", defaultValue = "300")
                    long defaultPlateauSeconds,
            @ConfigProperty(name = "planning.solver.seconds-limit-max", defaultValue = "3600") long maxSecondsLimit) {
        this(new SolverBudgetBounds(defaultSecondsLimit, Math.max(0, defaultPlateauSeconds), maxSecondsLimit));
    }

    /** For the plain tests: the bounds as given. */
    SolveBudgetPolicy(SolverBudgetBounds bounds) {
        this.bounds = bounds;
    }

    public SolverBudgetBounds bounds() {
        return bounds;
    }

    /**
     * Refuses to start under ceilings that contradict the defaults, the same
     * contract as {@code planning.journal.retention}: an instance whose every
     * edition left at the default would be refused its own launches, and the
     * only trace of the mistake would be the first organiser to click
     * « Calculer ».
     */
    void checkBounds(@Observes StartupEvent startup) {
        checkBounds(bounds);
    }

    static void checkBounds(SolverBudgetBounds bounds) {
        if (bounds.maxSecondsLimit() < bounds.defaultSecondsLimit()) {
            throw new IllegalStateException("planning.solver.seconds-limit-max (SOLVER_SECONDS_LIMIT_MAX) is "
                    + bounds.maxSecondsLimit() + " s, below the default planning.solver.seconds-limit of "
                    + bounds.defaultSecondsLimit() + " s: raise the ceiling or lower the default.");
        }
    }

    /**
     * Refuses a duration asked for at launch that the instance does not allow.
     * {@code null} asks for nothing and always passes.
     */
    public void checkRequested(Long requestedSeconds) {
        if (requestedSeconds == null) {
            return;
        }
        if (requestedSeconds <= 0) {
            throw new BusinessError.Invalid("La durée de résolution demandée doit être strictement positive.");
        }
        if (requestedSeconds > bounds.maxSecondsLimit()) {
            throw new BusinessError.Invalid("La durée de résolution demandée (" + humanDuration(requestedSeconds)
                    + ") dépasse le plafond de l'instance : au plus " + humanDuration(bounds.maxSecondsLimit())
                    + ", fixé par l'exploitant.");
        }
    }

    /**
     * The budget of a full solve: the duration asked for at launch, else the
     * instance's, and the instance's plateau.
     *
     * <p>Under a duration <b>asked for at launch</b> that differs from the
     * default, the plateau keeps its historical meaning of "exactly that
     * long": that is how the scenario harnesses and the test profile's
     * two-second plateau stay out of each other's way.</p>
     */
    public SolveBudget forSolve(Long requestedSeconds) {
        checkRequested(requestedSeconds);
        long seconds = requestedSeconds != null ? requestedSeconds : bounds.defaultSecondsLimit();
        long plateau = requestedSeconds != null && requestedSeconds != bounds.defaultSecondsLimit()
                ? 0
                : bounds.defaultPlateauSeconds();
        return new SolveBudget(seconds, plateau);
    }

    /**
     * The budget of an incremental re-solve: its own short duration unless one
     * is asked for — it refills a handful of seats, not the edition — with no
     * plateau bailout, as it has always run.
     */
    public SolveBudget forIncremental(Long requestedSeconds) {
        checkRequested(requestedSeconds);
        long seconds = requestedSeconds != null
                ? requestedSeconds
                : Math.min(INCREMENTAL_DEFAULT_SECONDS, bounds.maxSecondsLimit());
        return new SolveBudget(seconds, 0L);
    }

    /** « 2 h », « 15 min », « 90 s » — the unit a person would say it in. */
    public static String humanDuration(long seconds) {
        if (seconds != 0 && seconds % 3600 == 0) {
            return seconds / 3600 + " h";
        }
        if (seconds != 0 && seconds % 60 == 0) {
            return seconds / 60 + " min";
        }
        return seconds + " s";
    }
}
