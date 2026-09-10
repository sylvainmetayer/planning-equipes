package dev.sylvain.planning.service.solve;

import java.util.concurrent.atomic.AtomicLong;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Announces "the solver state just changed" to every open
 * {@code GET /api/jobs/stream}, so a browser learns about a hand-over in the
 * second it happens instead of at its next poll.
 *
 * <p>A {@link BroadcastProcessor} rather than a single stream: the state is
 * server-wide and every client is entitled to it — several tabs, several
 * browsers, several people. Each subscriber gets its own subscription to the
 * same source, and a subscriber leaving (a closed tab) never terminates it for
 * the others.</p>
 *
 * <p>What travels is a version number, not the state itself. The snapshot is
 * built by the endpoint, when it sends: a burst of transitions therefore
 * collapses into whatever is true at that moment instead of replaying a
 * sequence, and this service keeps knowing nothing about the wire format.
 * Nothing is buffered for a client that is not connected — the browser's
 * polling fallback is what repairs a missed window, not this.</p>
 */
@ApplicationScoped
public class JobStreamBroadcaster {

    private final BroadcastProcessor<Long> changes = BroadcastProcessor.create();
    private final AtomicLong version = new AtomicLong();

    /**
     * Signals a transition. Called at the end of the state changes of
     * {@link SolverJobService}, never in the middle of one: a subscriber reads
     * the state back synchronously, and must not see a half-applied hand-over.
     */
    public void publish() {
        changes.onNext(version.incrementAndGet());
    }

    /** Every transition from now on. Nothing is replayed on subscription. */
    public Multi<Long> changes() {
        return changes;
    }
}
