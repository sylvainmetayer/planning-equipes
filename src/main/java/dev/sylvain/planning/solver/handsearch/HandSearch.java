package dev.sylvain.planning.solver.handsearch;

import static dev.sylvain.planning.solver.handsearch.FlatProblem.MEDIUM;

import ai.timefold.solver.core.api.score.HardMediumSoftScore;
import dev.sylvain.planning.domain.PlanningEvenement;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.function.BooleanSupplier;

/**
 * A local search written by hand over {@link IncrementalScore} (ADR 0087): a
 * greedy fill, then late acceptance over single moves and swaps, moves aimed
 * at what is broken, and an ejection chain for the last empty seats.
 *
 * <p>It solves a problem <b>already prepared</b> — completed from the edition,
 * its past pinned — and reads nothing else: no configuration, no container, no
 * database, which is what lets it run in a worker as it runs here. Pinned and
 * past seats keep their holder; every other seat starts from the holder the
 * problem gives it, so a plan handed in is the plan the search starts from.</p>
 *
 * <p>The score it returns is its own. The caller has the plan scored again by
 * the constraint provider and refuses it when the two differ: this class is
 * fast because it does not go through Timefold, and believed only because
 * Timefold agrees.</p>
 */
public final class HandSearch {

    /**
     * When to stop: {@code seconds} after this search was built, or —
     * {@code plateauSeconds} above zero — once the plan is feasible and has
     * stopped gaining {@code plateauGainMedium} medium points a window of
     * {@code plateauSeconds}, as {@link Plateau} judges it.
     */
    public record Limits(long seconds, long plateauSeconds, long plateauGainMedium, long seed) {}

    /** Told each new best score, at most a few times a second. */
    @FunctionalInterface
    public interface Listener {

        /**
         * @param score the engine's own score of its best plan — a claim, until
         *              somebody has the plan scored again
         * @param lay   puts that plan on the problem's movable seats, for a
         *              caller that wants it refereed before it believes the
         *              score; only meaningful during the call
         */
        void best(long millisSpent, HardMediumSoftScore score, Runnable lay);
    }

    /** Scores kept by late acceptance: a move is taken when it beats the plan of that many moves ago. */
    private static final int LATE_ACCEPTANCE = 500;

    /** Moves between two looks at the clock, the stop flag and the best score. */
    private static final int TICK = 4096;

    private static final long ANNOUNCE_EVERY_NANOS = 250_000_000L;
    private static final long CHECK_EVERY_NANOS = 1_000_000_000L;

    /** The ejection chain runs on the last empty seats only: the random moves fill the others faster. */
    private static final int CHAIN_SEATS = 10;

    private static final long CHAIN_EVERY_NANOS = 2_000_000_000L;
    private static final int CHAIN_TRIALS = 15_000;
    private static final int CHAIN_DEPTH = 3;

    /** Trials of a chain between two looks at the clock and the stop flag. */
    private static final int CHAIN_LOOK = 256;

    private final long start = System.nanoTime();
    private final FlatProblem flat;
    private final IncrementalScore score;
    private long chainBudget;
    private long deadline;
    private BooleanSupplier stop;
    private boolean over;

    /**
     * Lays the problem flat; the time it takes counts in the budget of
     * {@link #solve}.
     *
     * @throws HandSearchException when the problem has a shape the engine does
     *                             not score (see {@link FlatProblem})
     */
    public HandSearch(PlanningEvenement prepared) {
        this.flat = new FlatProblem(prepared);
        this.score = new IncrementalScore(flat);
    }

    /**
     * Searches until a limit or {@code stop}, lays the best plan found on the
     * problem's movable seats and returns its score.
     *
     * @throws HandSearchException when a plan leaves the range the engine can
     *                             order, or — a defect — its running total
     *                             stopped matching the score rebuilt from the
     *                             plan alone
     */
    @SuppressWarnings("java:S3776") // one loop, read top to bottom; splitting it would only pass its locals around
    public HardMediumSoftScore solve(Limits limits, BooleanSupplier stopRequested, Listener listener) {
        this.deadline = start + limits.seconds() * 1_000_000_000L;
        this.stop = stopRequested;
        SplittableRandom random = new SplittableRandom(limits.seed());
        IncrementalScore.checkRange(score.levels());
        greedy();
        IncrementalScore.checkRange(score.levels());
        long totalHard = score.totalHard();
        long total = score.total();
        int nS = flat.nS;
        int nA = flat.nA;

        long[] history = new long[LATE_ACCEPTANCE];
        long[] historyHard = new long[LATE_ACCEPTANCE];
        Arrays.fill(history, total);
        Arrays.fill(historyHard, totalHard);
        long best = total;
        long bestHard = totalHard;
        int[] bestPlan = score.assign.clone();
        Runnable layBest = () -> lay(bestPlan);
        listener.best(millisSince(start), decoded(bestHard, best), layBest);
        long announced = best;
        long announcedHard = bestHard;
        long lastAnnounce = System.nanoTime();
        long lastCheck = lastAnnounce;
        long lastChain = lastAnnounce;
        Plateau plateau = new Plateau(limits.plateauSeconds() * 1_000_000_000L, limits.plateauGainMedium());
        boolean plateauOpen = false;
        long plateauSeen = 0;
        // Nobody to seat, or every seat pinned or past: the plan is scored as it stands.
        boolean movable = nA > 0 && !allFixed();
        for (long move = 0; movable; move++) {
            if (move % TICK == 0) {
                long now = System.nanoTime();
                if (before(bestHard, best, announcedHard, announced)
                        && (now - lastAnnounce >= ANNOUNCE_EVERY_NANOS || (bestHard == 0) != (announcedHard == 0))) {
                    listener.best(millisSince(start), decoded(bestHard, best), layBest);
                    announced = best;
                    announcedHard = bestHard;
                    lastAnnounce = now;
                }
                if (now - lastCheck >= CHECK_EVERY_NANOS) {
                    IncrementalScore.checkRange(score.levels());
                    lastCheck = now;
                }
                if (bestHard == 0) {
                    if (!plateauOpen || best < plateauSeen) {
                        plateau.best(now, Math.floorDiv(best + MEDIUM / 2, MEDIUM));
                        plateauOpen = true;
                        plateauSeen = best;
                    }
                    if (plateau.over(now)) {
                        break;
                    }
                }
                if (now >= deadline || stop.getAsBoolean()) {
                    break;
                }
                if (score.unassignedMovable > 0
                        && score.unassignedMovable <= CHAIN_SEATS
                        && now - lastChain >= CHAIN_EVERY_NANOS) {
                    chainEmptySeats(random);
                    totalHard = score.totalHard();
                    total = score.total();
                    Arrays.fill(history, total);
                    Arrays.fill(historyHard, totalHard);
                    if (before(totalHard, total, bestHard, best)) {
                        best = total;
                        bestHard = totalHard;
                        System.arraycopy(score.assign, 0, bestPlan, 0, nS);
                    }
                    lastChain = System.nanoTime();
                }
            }
            score.begin();
            int kind = random.nextInt(16);
            if (score.unassignedMovable > 0 && kind < 4) {
                // an empty seat: fill it, and half the time hand a colliding seat to a colleague
                int s = random.nextInt(nS);
                while (score.assign[s] >= 0 || flat.sFixed[s]) {
                    s = s + 1 == nS ? 0 : s + 1;
                }
                int[] candidates = flat.elig[s];
                if (candidates.length == 0) {
                    continue;
                }
                int a = candidates[random.nextInt(candidates.length)];
                score.move(s, a);
                if ((kind & 1) == 0) {
                    eject(s, a, random);
                }
            } else if (totalHard > 0 && kind < 9) {
                // somebody carrying a hard breach: move one of their seats, or trade it
                int a = random.nextInt(nA);
                for (int looked = 0; score.hardOf[a] == 0 && looked < nA; looked++) {
                    a = a + 1 == nA ? 0 : a + 1;
                }
                if (score.countOf[a] == 0) {
                    continue;
                }
                int s = score.seatsOf[a][random.nextInt(score.countOf[a])];
                if (flat.sFixed[s]) {
                    continue;
                }
                if ((kind & 1) == 0) {
                    int[] candidates = flat.elig[s];
                    if (candidates.length == 0) {
                        continue;
                    }
                    int b = candidates[random.nextInt(candidates.length)];
                    if (b == a) {
                        continue;
                    }
                    score.move(s, b);
                    if (kind < 7) {
                        eject(s, b, random);
                    }
                } else if (!swap(s, random.nextInt(nS))) {
                    continue;
                }
            } else if ((kind & 1) == 0) {
                int s = random.nextInt(nS);
                int[] candidates = flat.elig[s];
                if (flat.sFixed[s] || candidates.length == 0) {
                    continue;
                }
                int a = candidates[random.nextInt(candidates.length)];
                if (a == score.assign[s]) {
                    continue;
                }
                score.move(s, a);
            } else if (!swap(random.nextInt(nS), random.nextInt(nS))) {
                continue;
            }
            long candidate = total + score.finish();
            long candidateHard = totalHard + score.deltaHard;
            int slot = (int) (move % LATE_ACCEPTANCE);
            if (!before(totalHard, total, candidateHard, candidate)
                    || !before(historyHard[slot], history[slot], candidateHard, candidate)) {
                total = candidate;
                totalHard = candidateHard;
                if (before(totalHard, total, bestHard, best)) {
                    best = total;
                    bestHard = totalHard;
                    System.arraycopy(score.assign, 0, bestPlan, 0, nS);
                }
            } else {
                score.undo();
            }
            history[slot] = total;
            historyHard[slot] = totalHard;
        }

        // Laid first: should the check below fail, the plan is there to be read.
        lay(bestPlan);
        HardMediumSoftScore exact = rebuilt(bestPlan, bestHard, best);
        if (before(bestHard, best, announcedHard, announced)) {
            listener.best(millisSince(start), exact, layBest);
        }
        return exact;
    }

    /**
     * The score of the best plan, by a second instance built from that plan
     * alone: every count {@link IncrementalScore#set} kept up to date through
     * the search is rebuilt, where re-reading them would only repeat a count
     * that had drifted.
     */
    private HardMediumSoftScore rebuilt(int[] plan, long hard, long total) {
        IncrementalScore fresh = new IncrementalScore(flat);
        long freshTotal = fresh.load(plan);
        long freshHard = fresh.totalHard();
        long[] levels = fresh.levels();
        IncrementalScore.checkRange(levels);
        HardMediumSoftScore exact = HardMediumSoftScore.of(-levels[0], -levels[1], -levels[2]);
        if (freshHard != hard || freshTotal != total || !exact.equals(decoded(hard, total))) {
            throw HandSearchException.defect("le score tenu au fil des mouvements (" + decoded(hard, total)
                    + ") n'est pas celui du plan relu de zéro (" + exact + ")");
        }
        return exact;
    }

    private void lay(int[] plan) {
        for (int s = 0; s < flat.nS; s++) {
            if (!flat.sFixed[s]) {
                flat.postes.get(s).setAnimateur(plan[s] < 0 ? null : flat.animateurs.get(plan[s]));
            }
        }
    }

    private boolean allFixed() {
        for (boolean fixed : flat.sFixed) {
            if (!fixed) {
                return false;
            }
        }
        return true;
    }

    /**
     * The engine's own score of the plan a problem carries, rule by rule under
     * the constraint's name: points lost on the rule's level, 0 for a rule
     * switched off. What {@code HandSearchDifferentialTest} holds equal to a
     * Timefold score analysis, and what a divergence is read with.
     */
    public static Map<String, Long> breakdown(PlanningEvenement plan) {
        long[] points = new IncrementalScore(new FlatProblem(plan)).breakdown();
        Map<String, Long> byName = new LinkedHashMap<>();
        for (Rule rule : Rule.values()) {
            byName.put(rule.constraintName(), points[rule.ordinal()]);
        }
        return byName;
    }

    private static long millisSince(long start) {
        return (System.nanoTime() - start) / 1_000_000L;
    }

    /** Whether the first cost is strictly the better one: fewer hard points, then less of the rest. */
    private static boolean before(long hard, long rest, long otherHard, long otherRest) {
        return hard < otherHard || (hard == otherHard && rest < otherRest);
    }

    /** The score two numbers stand for; exact inside the range {@link IncrementalScore#checkRange} holds. */
    private static HardMediumSoftScore decoded(long hard, long rest) {
        long medium = Math.floorDiv(rest + MEDIUM / 2, MEDIUM);
        return HardMediumSoftScore.of(-hard, -medium, -(rest - medium * MEDIUM));
    }

    private boolean expired() {
        over = over || System.nanoTime() >= deadline || stop.getAsBoolean();
        return over;
    }

    /** Fills the empty seats, scarcest first, each with whoever it costs least; leaves the ones that only cost. */
    private void greedy() {
        Integer[] order = new Integer[flat.nS];
        for (int s = 0; s < flat.nS; s++) {
            order[s] = s;
        }
        Arrays.sort(order, Comparator.comparingInt(s -> flat.elig[s].length));
        for (int s : order) {
            if (flat.sFixed[s] || score.assign[s] >= 0) {
                continue;
            }
            if (expired()) {
                break;
            }
            long bestDelta = 0;
            long bestDeltaHard = 0;
            int bestHolder = -1;
            for (int a : flat.elig[s]) {
                score.begin();
                score.move(s, a);
                long delta = score.finish();
                long deltaHard = score.deltaHard;
                score.undo();
                if (before(deltaHard, delta, bestDeltaHard, bestDelta)) {
                    bestDelta = delta;
                    bestDeltaHard = deltaHard;
                    bestHolder = a;
                }
            }
            if (bestHolder >= 0) {
                score.begin();
                score.move(s, bestHolder);
                score.finish();
            }
        }
    }

    /** As part of the move under way: hands another seat of {@code a}, on a day around seat s, to somebody else. */
    private void eject(int s, int a, SplittableRandom random) {
        int[] seats = score.seatsOf[a];
        int n = score.countOf[a];
        int offset = random.nextInt(n);
        for (int i = 0; i < n; i++) {
            int other = seats[(i + offset) % n];
            if (other != s
                    && !flat.sFixed[other]
                    && Math.abs(flat.sDay[other] - flat.sDay[s]) <= 1
                    && flat.elig[other].length > 0) {
                int b = flat.elig[other][random.nextInt(flat.elig[other].length)];
                if (b != a) {
                    score.move(other, b);
                }
                return;
            }
        }
    }

    private boolean swap(int s1, int s2) {
        int a1 = score.assign[s1];
        int a2 = score.assign[s2];
        if (flat.sFixed[s1]
                || flat.sFixed[s2]
                || a1 == a2
                || a1 < 0
                || a2 < 0
                || flat.statHard[s1][a2] != 0
                || flat.statHard[s2][a1] != 0) {
            return false;
        }
        score.move(s1, a2);
        score.move(s2, a1);
        return true;
    }

    /** One ejection chain per empty seat, as long as the budget and the caller allow. */
    private void chainEmptySeats(SplittableRandom random) {
        for (int s = 0; s < flat.nS && !expired(); s++) {
            if (score.assign[s] < 0 && !flat.sFixed[s]) {
                chainBudget = CHAIN_TRIALS;
                place(s, 0, random);
            }
        }
    }

    /** Counts one trial of a chain; false once its budget, the solve's or the caller's patience is spent. */
    private boolean trial() {
        chainBudget--;
        return chainBudget > 0 && (chainBudget % CHAIN_LOOK != 0 || !expired());
    }

    /**
     * An ejection chain for the empty seat x: somebody takes it at no hard
     * cost, or somebody takes it and gives up one seat whose release alone
     * pays for it — that seat is then the one to place, a level deeper. Only
     * the releases that pay are followed, which keeps the search to thousands
     * of trials where trying every colleague for every seat took hundreds of
     * thousands. Leaves the plan changed when it returns true, as it was
     * otherwise.
     */
    private boolean place(int x, int depth, SplittableRandom random) {
        int[] candidates = flat.elig[x];
        if (candidates.length == 0) {
            return false;
        }
        int offset = random.nextInt(candidates.length);
        for (int i = 0; i < candidates.length; i++) {
            int a = candidates[(i + offset) % candidates.length];
            score.begin();
            score.move(x, a);
            score.finish();
            if (score.deltaHard <= -1) {
                return true;
            }
            score.undo();
            if (!trial()) {
                return false;
            }
        }
        if (depth >= CHAIN_DEPTH) {
            return false;
        }
        for (int i = 0; i < candidates.length; i++) {
            int a = candidates[(i + offset) % candidates.length];
            int[] theirs = Arrays.copyOf(score.seatsOf[a], score.countOf[a]);
            for (int released : theirs) {
                if (flat.sFixed[released] || Math.abs(flat.sWeek[released] - flat.sWeek[x]) > 1) {
                    continue;
                }
                score.begin();
                score.move(x, a);
                score.move(released, -1);
                score.finish();
                if (score.deltaHard > 0) {
                    score.undo();
                } else if (place(released, depth + 1, random)) {
                    return true;
                } else {
                    score.begin();
                    score.move(released, a);
                    score.move(x, -1);
                    score.finish();
                }
                if (!trial()) {
                    return false;
                }
            }
        }
        return false;
    }
}
