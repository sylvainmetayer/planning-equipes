package dev.sylvain.planning.service.solve;

import ai.timefold.solver.core.api.score.HardMediumSoftScore;
import ai.timefold.solver.core.api.solver.SolutionManager;
import ai.timefold.solver.core.api.solver.Solver;
import ai.timefold.solver.core.api.solver.change.ProblemChange;
import ai.timefold.solver.core.api.solver.event.BestSolutionChangedEvent;
import ai.timefold.solver.core.api.solver.event.SolverEventListener;
import dev.sylvain.planning.domain.PlanningEvenement;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.jboss.logging.Logger;

/**
 * A solve run on a worker, behind the face of a {@link Solver} (ADR 0086).
 *
 * <p>That face is the point: a job holds a solver to stop it, follows its
 * best scores for the curve and the moment the plan becomes feasible, and none
 * of that code knows whether the search runs in this process. Here
 * {@link #solve} sends the prepared problem, polls the worker once a second,
 * replays its curve as the events a local solver would have fired, and lays
 * the plan that comes back on the application's own problem — scored again
 * before it is believed ({@link SolveExchange#land}).</p>
 *
 * <p>What it does not do is change a problem under way: a worker's problem is
 * sent whole, once.</p>
 */
final class RemoteSolver implements Solver<PlanningEvenement> {

    private static final Logger LOG = Logger.getLogger(RemoteSolver.class);

    /** How long the worker may stay unreachable before the solve is given up; it goes on solving meanwhile. */
    static final Duration LOST_AFTER = Duration.ofMinutes(2);

    private static final int LOG_LINES_KEPT = 15;

    /** Hands over the worker to solve on, starting its machine first if it has to. */
    @FunctionalInterface
    interface Machine {

        /**
         * @param cancelled read while waiting for a machine being prepared:
         *                  true ends the wait
         * @throws Failed when no worker can be had
         */
        WorkerClient acquire(BooleanSupplier cancelled);
    }

    /** A solve already under way on the worker, found again after a restart: followed, not sent again. */
    record Resume(String solveId, Map<String, String> realIds) {}

    /** Told once the worker has taken the problem — what a restart needs to find this solve again. */
    @FunctionalInterface
    interface Journal {
        void submitted(String solveId, Map<String, String> realIds);
    }

    /** The remote solve failed; the persisted plan was not touched. The message is what the job shows. */
    static final class Failed extends RuntimeException {
        Failed(String message) {
            super(message);
        }

        Failed(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Stopped by hand before the worker had the problem: there is no plan to hand back. */
    static final class Cancelled extends RuntimeException {
        Cancelled() {
            super("Arrêtée avant que la machine de calcul ait reçu le problème.");
        }
    }

    /**
     * The application is going down and leaves the worker solving: the job is
     * not over, and the next start follows it again.
     */
    static final class Detached extends RuntimeException {
        Detached() {
            super("The application stopped; the worker goes on solving");
        }
    }

    private final Machine machine;
    private final SolveExchange exchange;
    private final SolutionManager<PlanningEvenement, HardMediumSoftScore> solutionManager;
    private final SolverConfiguration.SearchSettings settings;
    private final String version;
    private final Resume resume;
    private final Journal journal;
    private final BooleanSupplier shuttingDown;
    private final Duration pollEvery;

    private final List<SolverEventListener<PlanningEvenement>> listeners = new CopyOnWriteArrayList<>();
    private volatile boolean stopRequested;
    private volatile boolean solving;
    private final AtomicReference<FeasibilityFirstReport> feasibilityFirst = new AtomicReference<>();

    @SuppressWarnings("java:S107") // one collaborator per thing a remote solve needs; a holder would only rename them
    RemoteSolver(
            Machine machine,
            SolveExchange exchange,
            SolutionManager<PlanningEvenement, HardMediumSoftScore> solutionManager,
            SolverConfiguration.SearchSettings settings,
            String version,
            Resume resume,
            Journal journal,
            BooleanSupplier shuttingDown,
            Duration pollEvery) {
        this.machine = machine;
        this.exchange = exchange;
        this.solutionManager = solutionManager;
        this.settings = settings;
        this.version = version;
        this.resume = resume;
        this.journal = journal;
        this.shuttingDown = shuttingDown;
        this.pollEvery = pollEvery;
    }

    /** The two stages of the solve the worker ran, when it had two; {@code null} otherwise. */
    FeasibilityFirstReport feasibilityFirst() {
        return feasibilityFirst.get();
    }

    /**
     * Solves {@code prepared} on the worker and returns it carrying the plan —
     * the same instance, unlike a local solver's clone: the plan is laid on the
     * application's problem, which never left.
     */
    @Override
    public PlanningEvenement solve(PlanningEvenement prepared) {
        solving = true;
        try {
            return solveRemotely(prepared);
        } finally {
            solving = false;
        }
    }

    private PlanningEvenement solveRemotely(PlanningEvenement prepared) {
        long start = System.nanoTime();
        WorkerClient worker = machine.acquire(() -> stopRequested);
        if (stopRequested) {
            throw shuttingDown.getAsBoolean() ? new Detached() : new Cancelled();
        }
        String solveId;
        Map<String, String> realIds;
        long offsetMs;
        if (resume != null) {
            solveId = resume.solveId();
            realIds = resume.realIds();
            offsetMs = 0;
        } else {
            SolveExchange.Outbound outbound = exchange.outbound(prepared);
            solveId = submit(worker, outbound);
            realIds = outbound.realIds();
            journal.submitted(solveId, realIds);
            // The curve is the job's: the worker's clock starts once the
            // machine is up and the problem received.
            offsetMs = (System.nanoTime() - start) / 1_000_000L;
        }
        SolveWorker.Answer answer = follow(worker, solveId, offsetMs);
        try {
            exchange.land(
                    prepared, new SolveExchange.Result(answer.holders(), answer.score()), realIds, solutionManager);
        } catch (SolveExchange.Refused e) {
            throw new Failed("Plan refusé à son retour de la machine de calcul : " + e.getMessage(), e);
        }
        feasibilityFirst.set(answer.feasibilityFirst());
        return prepared;
    }

    private String submit(WorkerClient worker, SolveExchange.Outbound outbound) {
        try {
            String running = worker.version();
            if (!version.equals(running)) {
                throw new Failed("Machine de calcul indisponible : elle tourne en version " + running
                        + ", l'application en version " + version + ".");
            }
            return worker.submit(outbound.payload(), settings, version);
        } catch (WorkerClient.Refused | UncheckedIOException e) {
            throw new Failed("Machine de calcul indisponible : " + e.getMessage(), e);
        }
    }

    private SolveWorker.Answer follow(WorkerClient worker, String solveId, long offsetMs) {
        int cursor = 0;
        boolean stopSent = false;
        long lastContact = System.nanoTime();
        while (true) {
            try {
                if (stopRequested && !stopSent) {
                    if (shuttingDown.getAsBoolean()) {
                        throw new Detached();
                    }
                    worker.stop(solveId);
                    stopSent = true;
                }
                SolveWorker.Status status = worker.status(solveId, cursor);
                lastContact = System.nanoTime();
                for (SolveWorker.Point point : status.points()) {
                    fire(new BestScore(
                            offsetMs + point.timeMs(),
                            HardMediumSoftScore.of(point.hard(), point.medium(), point.soft())));
                }
                cursor = status.next();
                if (status.state() == SolveWorker.State.DONE) {
                    return worker.answer(solveId);
                }
                if (status.state() == SolveWorker.State.FAILED) {
                    throw new Failed(
                            "Le calcul a échoué sur la machine de calcul : " + status.error() + logsOf(worker));
                }
            } catch (WorkerClient.Refused e) {
                if (e.status() < 500) {
                    // The worker answers and no longer knows this solve: it
                    // restarted, or solves something else. Nothing to wait for.
                    throw new Failed("La machine de calcul a perdu ce calcul : " + e.getMessage() + logsOf(worker), e);
                }
                // A gateway in front of it saying 502 for one poll is a cut
                // like any other: the worker goes on solving.
                giveUpIfLost(lastContact, e);
            } catch (UncheckedIOException e) {
                giveUpIfLost(lastContact, e);
            }
            pause();
        }
    }

    private static void giveUpIfLost(long lastContact, RuntimeException e) {
        if (System.nanoTime() - lastContact > LOST_AFTER.toNanos()) {
            throw new Failed(
                    "Machine de calcul injoignable depuis " + LOST_AFTER.toMinutes()
                            + " minutes : calcul abandonné, le plan enregistré n'a pas été touché.",
                    e);
        }
        LOG.debugf(e, "The worker did not answer; trying again");
    }

    private void pause() {
        try {
            Thread.sleep(pollEvery);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            if (shuttingDown.getAsBoolean()) {
                throw new Detached();
            }
            throw new Failed("Suivi du calcul distant interrompu.");
        }
    }

    /** The end of the worker's log, for the job's error — or the fact that it could not be had. */
    private static String logsOf(WorkerClient worker) {
        try {
            List<String> lines = worker.logs();
            List<String> tail = lines.subList(Math.max(0, lines.size() - LOG_LINES_KEPT), lines.size());
            return tail.isEmpty() ? "" : "\nFin du journal de la machine :\n" + String.join("\n", tail);
        } catch (RuntimeException _) {
            return "\nLe journal de la machine n'a pas pu être récupéré.";
        }
    }

    private void fire(BestSolutionChangedEvent<PlanningEvenement> event) {
        for (SolverEventListener<PlanningEvenement> listener : listeners) {
            listener.bestSolutionChanged(event);
        }
    }

    @Override
    public boolean terminateEarly() {
        stopRequested = true;
        return true;
    }

    @Override
    public boolean isSolving() {
        return solving;
    }

    @Override
    public boolean isTerminateEarly() {
        return stopRequested;
    }

    @Override
    public void addProblemChange(ProblemChange<PlanningEvenement> problemChange) {
        throw new UnsupportedOperationException("A problem sent to a worker is not changed under way");
    }

    @Override
    public void addProblemChanges(List<ProblemChange<PlanningEvenement>> problemChangeList) {
        throw new UnsupportedOperationException("A problem sent to a worker is not changed under way");
    }

    @Override
    public boolean isEveryProblemChangeProcessed() {
        return true;
    }

    @Override
    public void addEventListener(SolverEventListener<PlanningEvenement> eventListener) {
        listeners.add(eventListener);
    }

    @Override
    public void removeEventListener(SolverEventListener<PlanningEvenement> eventListener) {
        listeners.remove(eventListener);
    }
}
