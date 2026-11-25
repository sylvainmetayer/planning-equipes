package dev.sylvain.planning.service.solve;

import ai.timefold.solver.core.api.score.HardMediumSoftScore;
import ai.timefold.solver.core.api.solver.Solver;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sylvain.planning.domain.PlanningEvenement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.jboss.logging.Logger;

/**
 * The worker side of a solve run elsewhere (ADR 0086): one problem at a time,
 * received through {@link SolveExchange}, searched exactly as the application
 * would have, and kept in memory until its plan is fetched.
 *
 * <p>It completes nothing and reads no setting of its own: the problem arrives
 * prepared and pinned, and the termination arrives with it. Nothing is written
 * to disk, and nothing here knows a name — the problem names nobody.</p>
 *
 * <p>No HTTP here: {@link SolveWorkerServer} is the transport, and a test
 * drives this class directly.</p>
 */
public final class SolveWorker {

    private static final Logger LOG = Logger.getLogger(SolveWorker.class);

    /** Shortest gap between two points of the curve a run keeps. */
    private static final long POINT_INTERVAL_MS = 500L;

    public enum State {
        RUNNING,
        DONE,
        FAILED
    }

    /** One best score: milliseconds since the run started, and the score. */
    public record Point(long timeMs, long hard, long medium, long soft) {}

    /**
     * Where a run stands.
     *
     * @param points the curve from the cursor asked for
     * @param next   the cursor to ask from next time
     * @param error  why a failed run failed
     */
    public record Status(State state, List<Point> points, int next, String error) {}

    /** The plan of a finished run: who holds each seat, the score claimed, and the two stages when it had two. */
    public record Answer(Map<String, String> holders, String score, FeasibilityFirstReport feasibilityFirst) {}

    /** A solve is already running: one at a time. */
    public static final class Busy extends RuntimeException {
        Busy() {
            super("A solve is already running on this worker");
        }
    }

    /** The id names no run this worker holds — never started here, or replaced by a later one. */
    public static final class Unknown extends RuntimeException {
        Unknown(String id) {
            super("Unknown solve " + id);
        }
    }

    private final SolveExchange exchange = new SolveExchange(new ObjectMapper());

    private final ExecutorService thread = Executors.newSingleThreadExecutor(runnable -> {
        Thread solving = new Thread(runnable, "solve-worker");
        solving.setDaemon(true);
        // The solver finds its configuration and its classes through the
        // thread's loader: this class's, whoever's thread started the run.
        solving.setContextClassLoader(SolveWorker.class.getClassLoader());
        return solving;
    });

    /** The one run kept: the running one, or the last finished until the next replaces it. */
    private Run current;

    /**
     * Starts solving {@code payload} and returns the id to follow it by.
     *
     * @throws Busy                 when a solve is running
     * @throws SolveExchange.Refused when the payload is not a problem this version reads
     */
    public synchronized String submit(byte[] payload, SolverConfiguration.SearchSettings settings) {
        if (isSolving()) {
            throw new Busy();
        }
        PlanningEvenement problem = exchange.read(payload);
        Run run = new Run();
        current = run;
        thread.execute(() -> run.execute(problem, settings));
        return run.id;
    }

    public synchronized boolean isSolving() {
        return current != null && current.state == State.RUNNING;
    }

    public Status status(String id, int cursor) {
        return run(id).status(cursor);
    }

    /** Asks the run to end now; it hands back the best plan it has. */
    public void stop(String id) {
        run(id).stop();
    }

    /** The plan of a finished run; {@code null} while it runs or when it failed. */
    public Answer answer(String id) {
        return run(id).answer.get();
    }

    private synchronized Run run(String id) {
        if (current == null || !current.id.equals(id)) {
            throw new Unknown(id);
        }
        return current;
    }

    private final class Run {

        private final String id = UUID.randomUUID().toString();
        private final long startNanos = System.nanoTime();
        private final List<Point> points = new ArrayList<>();

        /** The latest best not yet in {@link #points}: flushed when somebody reads. */
        private Point pending;

        private volatile State state = State.RUNNING;
        private volatile String error;
        private final AtomicReference<Answer> answer = new AtomicReference<>();
        private volatile boolean stopRequested;
        private final AtomicReference<Solver<PlanningEvenement>> solver = new AtomicReference<>();

        // Any Error too (OutOfMemoryError, StackOverflowError) must end as FAILED, or the caller polls forever.
        @SuppressWarnings("java:S1181")
        void execute(PlanningEvenement problem, SolverConfiguration.SearchSettings settings) {
            try {
                SolverConfiguration configuration = SolverConfiguration.ofSettings(settings);
                SolveRunner.Solved solved = new SolveRunner(configuration, null, null)
                        .solvePrepared(
                                problem,
                                new SolveBudget(settings.seconds(), settings.plateauSeconds(), null),
                                this::follow);
                SolveExchange.Result result = exchange.resultOf(solved.planning(), configuration.solutionManager());
                answer.set(new Answer(result.holders(), result.score(), solved.feasibilityFirst()));
                state = State.DONE;
                LOG.infof("Solve %s ended: %s", id, result.score());
            } catch (RuntimeException | Error e) {
                LOG.errorf(e, "Solve %s failed", id);
                error = e.toString();
                state = State.FAILED;
            }
        }

        /** One solver per stage of a two-stage solve: the latest is the one to stop, the curve goes on. */
        private void follow(Solver<PlanningEvenement> stage) {
            long offsetMs = (System.nanoTime() - startNanos) / 1_000_000L;
            synchronized (this) {
                // The first stage's last best was scored with stability
                // suspended: it is not where the second starts on the curve.
                pending = null;
            }
            stage.addEventListener(event -> {
                // Timefold clears a stop asked before solve() started: asked
                // again at each new best, the run's own flag has the last word.
                if (stopRequested && !stage.isTerminateEarly()) {
                    stage.terminateEarly();
                }
                if (event.isNewBestSolutionInitialized()
                        && event.getNewBestScore() instanceof HardMediumSoftScore score) {
                    recordPoint(new Point(
                            offsetMs + event.getTimeMillisSpent(),
                            score.hardScore(),
                            score.mediumScore(),
                            score.softScore()));
                }
            });
            solver.set(stage);
            if (stopRequested) {
                stage.terminateEarly();
            }
        }

        private synchronized void recordPoint(Point point) {
            if (points.isEmpty()
                    || point.timeMs() - points.get(points.size() - 1).timeMs() >= POINT_INTERVAL_MS) {
                points.add(point);
                pending = null;
            } else {
                pending = point;
            }
        }

        synchronized Status status(int cursor) {
            if (pending != null) {
                points.add(pending);
                pending = null;
            }
            int from = Math.clamp(cursor, 0, points.size());
            return new Status(state, List.copyOf(points.subList(from, points.size())), points.size(), error);
        }

        void stop() {
            stopRequested = true;
            Solver<PlanningEvenement> stage = solver.get();
            if (stage != null) {
                stage.terminateEarly();
            }
        }
    }
}
