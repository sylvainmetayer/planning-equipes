package dev.sylvain.planning.service.solve;

/**
 * Replays the persisted solver queue as a restart would, for a test that
 * lives outside this package — {@link SolverJobService#restaurer()} stays
 * package-private, and the {@code StartupEvent} that calls it is switched off
 * under {@code %test}.
 */
public final class QueueReplay {

    private QueueReplay() {}

    /** @return how many jobs went back into the queue */
    public static int replay(SolverJobService jobs) {
        return jobs.restaurer();
    }
}
