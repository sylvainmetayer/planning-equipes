package dev.sylvain.planning.service.solve;

/**
 * Settles the solver jobs a previous run left, as a restart would (nothing
 * queued comes back, ADR 0110), for a test that
 * lives outside this package — {@link SolverJobService#restaurer()} stays
 * package-private, and the {@code StartupEvent} that calls it is switched off
 * under {@code %test}.
 */
public final class QueueReplay {

    private QueueReplay() {}

    /** @return how many jobs went back to the solver (followed on their worker) */
    public static int replay(SolverJobService jobs) {
        return jobs.restaurer();
    }
}
