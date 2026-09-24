package dev.sylvain.planning.mcp;

import dev.sylvain.planning.service.solve.SolverJobService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Whether a solve holds the edition the current call works in — what
 * {@link WarnsWhileSolvingInterceptor} asks after the write.
 *
 * <p>A bean of its own solely so the suite can replace it, as {@code PgDump}
 * is: holding the solver for real takes a solve, and replacing
 * {@link SolverJobService} itself would take its queue, its stream and its
 * scheduler down with it. The rule stays in {@code SolverJobService}
 * ({@code activeJobForCurrentEdition}): another edition's job and a queued one
 * hold nothing here.</p>
 */
@ApplicationScoped
public class RunningSolveProbe {

    @Inject
    SolverJobService solverJobs;

    public boolean holdsCurrentEdition() {
        return solverJobs.activeJobForCurrentEdition().isPresent();
    }
}
