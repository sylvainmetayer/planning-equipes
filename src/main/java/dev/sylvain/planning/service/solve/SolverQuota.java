package dev.sylvain.planning.service.solve;

import dev.sylvain.planning.service.BusinessError;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.Deque;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The operator's guard on how much solving the instance accepts, whoever
 * asks: at most so many solver runs in the last sixty minutes, and at most so
 * many jobs waiting behind the running one (ADR 0075).
 *
 * <p>Meant for an instance that shares its machine with something that
 * matters more — a public demonstration next to production — where the
 * duration ceilings of {@link SolveBudgetPolicy} bound one run but nothing
 * bounded how many runs a visitor holding the shared password could chain.
 * Both limits are off by default ({@code 0}): an organiser's own instance has
 * no reason to refuse its organiser.</p>
 *
 * <p><b>Counted at submission, for everything that will take the cores</b>:
 * the solver jobs (from a body, from the reference data, incremental),
 * queued or not, the synchronous {@code POST /api/solve}, and the staffing
 * check, which is a real solve outside the queue. A job queued then removed
 * still counts — so does one refused later by the domain: what is guarded is
 * the asking, and a count that a « retirer de la file » gave back would be a
 * count one click erases. A job the persisted queue replays at startup was
 * counted when it was submitted and goes through neither check: it never
 * passes through here.</p>
 *
 * <p><b>In memory, on purpose.</b> A restart resets the count, which is
 * acceptable for an abuse guard — restarting the instance is not within the
 * reach of the visitor it aims at. Counting rows of {@code solver_job} instead
 * would not be more correct: the synchronous solve and the staffing check
 * write none, and deleting a finished job from the history deletes its row.</p>
 *
 * <p>The window <b>slides</b>: each run counts for exactly sixty minutes from
 * its submission, and the refusal can name the minute the oldest one leaves
 * it — unlike the fixed windows of {@code SlidingWindowCounter}, which would
 * let twice the quota through across a window boundary, the burst this guard
 * exists to stop.</p>
 */
@ApplicationScoped
public class SolverQuota {

    private static final Logger LOG = Logger.getLogger(SolverQuota.class);

    /** How long a run counts against the hourly quota. */
    static final Duration WINDOW = Duration.ofHours(1);

    private static final DateTimeFormatter HOUR_MINUTE = DateTimeFormatter.ofPattern("HH:mm");

    /** Runs accepted per sliding hour, {@code 0} or less for no quota. */
    private final int maxPerHour;

    /** Jobs waiting behind the running one, {@code 0} or less for no cap. */
    private final int maxQueued;

    /** The zone the next possible run is told in: the event's, not the container's UTC. */
    private final ZoneId zone;

    private final Clock clock;

    /** Submission instants still inside the window, oldest first. Guarded by this object's monitor. */
    private final Deque<Instant> submissions = new ArrayDeque<>();

    @Inject
    public SolverQuota(
            @ConfigProperty(name = "planning.solver.max-solves-per-hour", defaultValue = "0") int maxPerHour,
            @ConfigProperty(name = "planning.solver.max-queued-jobs", defaultValue = "0") int maxQueued,
            @ConfigProperty(name = "planning.notifications.zone", defaultValue = "Europe/Paris") String zone) {
        this(maxPerHour, maxQueued, zoneOf(zone), Clock.systemUTC());
    }

    /** For the plain tests: a clock they move. */
    SolverQuota(int maxPerHour, int maxQueued, ZoneId zone, Clock clock) {
        this.maxPerHour = maxPerHour;
        this.maxQueued = maxQueued;
        this.zone = zone;
        this.clock = clock;
    }

    private static ZoneId zoneOf(String zone) {
        try {
            return ZoneId.of(zone);
        } catch (RuntimeException _) {
            LOG.warnf("Unknown time zone %s for the solver quota, falling back on the system zone", zone);
            return ZoneId.systemDefault();
        }
    }

    /**
     * Counts one solver run, or refuses it when the last sixty minutes already
     * hold the quota. Called last, once every other refusal has had its say:
     * a launch refused for another reason consumes nothing.
     *
     * @throws BusinessError.Conflict over the quota, naming the minute the
     *                                next run will be accepted
     */
    public synchronized void consume() {
        if (maxPerHour <= 0) {
            return;
        }
        Instant now = clock.instant();
        Instant cutoff = now.minus(WINDOW);
        while (!submissions.isEmpty() && !submissions.peekFirst().isAfter(cutoff)) {
            submissions.pollFirst();
        }
        if (submissions.size() >= maxPerHour) {
            Instant next = submissions.peekFirst().plus(WINDOW);
            throw new BusinessError.Conflict("Cette instance limite le calcul à " + runs(maxPerHour)
                    + " par heure. Prochaine résolution possible à " + minuteOf(next) + ".");
        }
        submissions.addLast(now);
    }

    /**
     * Refuses to queue one more job when {@code waiting} jobs already wait
     * behind the running one. The running job is not one of them: it holds
     * the solver, it no longer waits for it.
     *
     * @throws BusinessError.Conflict at the cap
     */
    public void checkQueue(int waiting) {
        if (maxQueued > 0 && waiting >= maxQueued) {
            throw new BusinessError.Conflict("Cette instance limite la file d'attente du solveur à "
                    + runs(maxQueued) + " planifiée" + (maxQueued > 1 ? "s" : "")
                    + ". Attendez qu'une résolution démarre, ou retirez-en une de la file.");
        }
    }

    /**
     * The minute a run becomes possible again, rounded <b>up</b>: « 14:32 »
     * for 14:31:20 would send the reader back twenty seconds too early, to a
     * second refusal.
     */
    private String minuteOf(Instant instant) {
        ZonedDateTime local = instant.atZone(zone);
        ZonedDateTime minute = local.truncatedTo(ChronoUnit.MINUTES);
        return HOUR_MINUTE.format(minute.equals(local) ? minute : minute.plusMinutes(1));
    }

    private static String runs(int count) {
        return count + (count > 1 ? " résolutions" : " résolution");
    }
}
