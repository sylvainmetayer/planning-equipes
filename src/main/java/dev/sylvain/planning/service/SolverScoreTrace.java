package dev.sylvain.planning.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import ai.timefold.solver.core.api.score.HardMediumSoftScore;
import ai.timefold.solver.core.api.solver.Solver;
import ai.timefold.solver.core.api.solver.event.BestSolutionChangedEvent;

import dev.sylvain.planning.domain.PlanningEvenement;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The score of the solve currently running, over time (issue #304): what
 * Timefold already announces through {@code bestSolutionChanged}, kept just
 * long enough for a browser to draw it.
 *
 * <p>One run at a time, like the solver lock itself — there is nothing to key
 * this by. The trace of the last run is kept after it ends rather than dropped,
 * so a screen opened right after a solve still shows the curve it just
 * produced; the next run replaces it. Replaying <b>past</b> solves is
 * deliberately out of scope: nothing is persisted, and a restart forgets
 * everything.</p>
 *
 * <h2>Why this samples, and how</h2>
 *
 * <p>A solve announces a new best solution far more than once per second at the
 * start of the run — thousands of them over a full budget. Sending every one of
 * them would flood the stream and the browser for a curve that is a few hundred
 * pixels wide. So two bounds, both applied here rather than left to the
 * client:</p>
 *
 * <ul>
 *   <li><b>One point per {@link #INTERVALLE_INITIAL_MS} at most.</b> The point
 *       kept is the <em>latest</em> best of the window, never the first: the
 *       score only improves, so the newest value is the truthful one.</li>
 *   <li><b>{@link #MAX_POINTS} points at most, whatever the budget.</b> Once
 *       full, the series is halved — every other point is dropped — and the
 *       sampling interval doubles with it. A 900 s run and a 4 h run therefore
 *       cost the same memory and the same bytes on the wire, and the curve
 *       keeps its shape.</li>
 * </ul>
 *
 * <p>Events carrying a solution that is not initialized yet are skipped. Their
 * score describes a partial assignment — a plan with most seats still empty
 * violates almost nothing — so plotting them would draw a hard score falling
 * off a cliff at the exact moment the plan becomes complete, which reads as a
 * regression when it is the opposite.</p>
 *
 * <h2>Reading it back</h2>
 *
 * <p>{@link #generation()} is what makes an incremental read possible: it moves
 * whenever the series stops being an append-only extension of itself — a new
 * run, or a decimation. A reader that kept an older generation must take the
 * series again from the start; anything else only ever grows at the end.</p>
 */
@ApplicationScoped
public class SolverScoreTrace {

    /** Shortest gap between two recorded points, before any decimation. */
    static final long INTERVALLE_INITIAL_MS = 1000L;

    /** Series length above which it is halved and the interval doubled. */
    static final int MAX_POINTS = 400;

    /** One sample: milliseconds spent solving, and the best score at that time. */
    public record Point(long tempsMs, long hard, long medium, long soft) {
    }

    /**
     * The whole series as a reader sees it.
     *
     * @param jobId       run this describes
     * @param editionId   edition that run writes to — the client shows the curve
     *                    only on that edition, and {@code GET /api/jobs/score}
     *                    refuses it on any other
     * @param generation  see {@link SolverScoreTrace}
     * @param intervalleMs current sampling interval, after any decimation
     * @param termine     whether the run is over: the curve stops here
     */
    public record Trace(String jobId, String editionId, int generation, long intervalleMs,
            boolean termine, List<Point> points) {
    }

    private final List<Point> points = new ArrayList<>();
    private String jobId;
    private String editionId;
    private int generation;
    private long intervalleMs = INTERVALLE_INITIAL_MS;
    private boolean termine;
    /** Latest best announced, recorded or not: what {@link #finish} flushes. */
    private Point dernier;
    private long dernierAjoutMs;

    /**
     * Starts a trace for this job and follows the solver it was just handed.
     * Called from {@code SolverJobService} at the same moment the job takes
     * hold of its solver, so a run is followed from its first best solution.
     */
    public void follow(String jobId, String editionId, Solver<PlanningEvenement> solver) {
        start(jobId, editionId);
        solver.addEventListener(event -> record(jobId, event));
    }

    synchronized void start(String jobId, String editionId) {
        this.jobId = jobId;
        this.editionId = editionId;
        this.points.clear();
        this.intervalleMs = INTERVALLE_INITIAL_MS;
        this.termine = false;
        this.dernier = null;
        this.dernierAjoutMs = Long.MIN_VALUE;
        this.generation++;
    }

    /**
     * One announcement from the solver thread. Synchronized and allocation-shy
     * on purpose: this runs inside the solve, and must never be what slows it
     * down or what breaks it.
     */
    synchronized void record(String jobId, BestSolutionChangedEvent<PlanningEvenement> event) {
        if (!Objects.equals(this.jobId, jobId) || termine) {
            return;
        }
        if (!event.isNewBestSolutionInitialized()
                || !(event.getNewBestScore() instanceof HardMediumSoftScore score)) {
            return;
        }
        Point point = new Point(event.getTimeMillisSpent(),
                score.hardScore(), score.mediumScore(), score.softScore());
        dernier = point;
        if (points.isEmpty() || point.tempsMs() - dernierAjoutMs >= intervalleMs) {
            append(point);
        }
    }

    /**
     * Ends the trace of this job, whichever way it ended — completed, failed or
     * cancelled by hand. The last announced best is flushed even when the
     * sampling window had not elapsed: the final score is the one number of the
     * whole curve that must not be an approximation.
     */
    public synchronized void finish(String jobId) {
        if (!Objects.equals(this.jobId, jobId) || termine) {
            return;
        }
        if (dernier != null && (points.isEmpty() || points.get(points.size() - 1) != dernier)) {
            // Appended directly: a decimation here would move the generation
            // for one point, and force every reader to take the series again.
            points.add(dernier);
        }
        termine = true;
    }

    /** The series, or {@code null} when no solve has run since startup. */
    public synchronized Trace snapshot() {
        if (jobId == null) {
            return null;
        }
        return new Trace(jobId, editionId, generation, intervalleMs, termine, List.copyOf(points));
    }

    synchronized int generation() {
        return generation;
    }

    private void append(Point point) {
        points.add(point);
        dernierAjoutMs = point.tempsMs();
        if (points.size() >= MAX_POINTS) {
            decimate();
        }
    }

    /**
     * Halves the series and doubles the sampling interval, so a run of any
     * length costs a bounded amount of memory and of bytes on the wire. Every
     * other point goes, except the most recent one — that is the score as it
     * stands, and dropping it would make the curve lag a whole window behind.
     */
    private void decimate() {
        List<Point> gardes = new ArrayList<>(points.size() / 2 + 1);
        for (int i = 0; i < points.size(); i += 2) {
            gardes.add(points.get(i));
        }
        Point plusRecent = points.get(points.size() - 1);
        if (gardes.get(gardes.size() - 1) != plusRecent) {
            gardes.add(plusRecent);
        }
        points.clear();
        points.addAll(gardes);
        intervalleMs *= 2;
        generation++;
    }
}
