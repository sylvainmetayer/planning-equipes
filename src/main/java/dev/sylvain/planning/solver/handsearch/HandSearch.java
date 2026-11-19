package dev.sylvain.planning.solver.handsearch;

import static dev.sylvain.planning.solver.handsearch.FlatProblem.HARD;
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
     * When to stop: after {@code seconds}, or — {@code plateauSeconds} above
     * zero — once the plan is feasible and {@code plateauSeconds} went by
     * without the best medium score gaining {@code plateauGainMedium} points
     * (without any better plan at all when that gain is zero).
     */
    public record Limits(long seconds, long plateauSeconds, long plateauGainMedium, long seed) {}

    /** Told each new best score, at most a few times a second. */
    @FunctionalInterface
    public interface Listener {
        void best(long millisSpent, HardMediumSoftScore score);
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

    private final FlatProblem flat;
    private final IncrementalScore score;
    private long chainBudget;

    /**
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
     *                             order, or its running total stopped matching
     *                             the score read from scratch
     */
    @SuppressWarnings("java:S3776") // one loop, read top to bottom; splitting it would only pass its ten locals around
    public HardMediumSoftScore solve(Limits limits, BooleanSupplier stop, Listener listener) {
        long start = System.nanoTime();
        long deadline = start + limits.seconds() * 1_000_000_000L;
        SplittableRandom random = new SplittableRandom(limits.seed());
        IncrementalScore.checkRange(score.levels());
        long total = greedy(stop, deadline);
        IncrementalScore.checkRange(score.levels());
        listener.best(millisSince(start), decoded(total));

        long[] history = new long[LATE_ACCEPTANCE];
        Arrays.fill(history, total);
        long best = total;
        int[] bestPlan = score.assign.clone();
        long announced = total;
        long lastAnnounce = System.nanoTime();
        long lastCheck = lastAnnounce;
        long lastChain = lastAnnounce;
        // The plateau is judged from feasibility, against the best medium score it last started from.
        long plateauSince = -1;
        long plateauFrom = 0;
        int nS = flat.nS;
        int nA = flat.nA;
        // Nobody to seat, or every seat pinned or past: the plan is scored as it stands.
        boolean movable = nA > 0 && !allFixed();
        for (long move = 0; movable; move++) {
            if (move % TICK == 0) {
                long now = System.nanoTime();
                if (best < announced
                        && (now - lastAnnounce >= ANNOUNCE_EVERY_NANOS || (best < HARD) != (announced < HARD))) {
                    listener.best(millisSince(start), decoded(best));
                    announced = best;
                    lastAnnounce = now;
                }
                if (now - lastCheck >= CHECK_EVERY_NANOS) {
                    IncrementalScore.checkRange(score.levels());
                    lastCheck = now;
                }
                if (best < HARD && limits.plateauSeconds() > 0) {
                    long medium = Math.floorDiv(best + MEDIUM / 2, MEDIUM);
                    boolean gained = limits.plateauGainMedium() > 0
                            ? plateauFrom - medium >= limits.plateauGainMedium()
                            : best < plateauFrom;
                    if (plateauSince < 0 || gained) {
                        plateauSince = now;
                        plateauFrom = limits.plateauGainMedium() > 0 ? medium : best;
                    } else if (now - plateauSince >= limits.plateauSeconds() * 1_000_000_000L) {
                        break;
                    }
                }
                if (now >= deadline || stop.getAsBoolean()) {
                    break;
                }
                if (score.unassignedMovable > 0
                        && score.unassignedMovable <= CHAIN_SEATS
                        && now - lastChain >= CHAIN_EVERY_NANOS) {
                    total = chainEmptySeats(random, stop);
                    Arrays.fill(history, total);
                    if (total < best) {
                        best = total;
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
            } else if (total >= HARD && kind < 9) {
                // somebody carrying a hard breach: move one of their seats, or trade it
                int a = random.nextInt(nA);
                for (int looked = 0; score.costOf[a] < HARD && looked < nA; looked++) {
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
            int slot = (int) (move % LATE_ACCEPTANCE);
            if (candidate <= total || candidate <= history[slot]) {
                total = candidate;
                if (total < best) {
                    best = total;
                    System.arraycopy(score.assign, 0, bestPlan, 0, nS);
                }
            } else {
                score.undo();
            }
            history[slot] = total;
        }

        score.load(bestPlan);
        long[] levels = score.levels();
        IncrementalScore.checkRange(levels);
        HardMediumSoftScore exact = HardMediumSoftScore.of(-levels[0], -levels[1], -levels[2]);
        if (!exact.equals(decoded(best)) || score.recompute() != best) {
            throw new HandSearchException("le total tenu au fil des mouvements (" + decoded(best)
                    + ") a dérivé du score relu (" + exact + ")");
        }
        for (int s = 0; s < nS; s++) {
            if (!flat.sFixed[s]) {
                flat.postes.get(s).setAnimateur(bestPlan[s] < 0 ? null : flat.animateurs.get(bestPlan[s]));
            }
        }
        if (best < announced) {
            listener.best(millisSince(start), exact);
        }
        return exact;
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

    private boolean allFixed() {
        for (boolean fixed : flat.sFixed) {
            if (!fixed) {
                return false;
            }
        }
        return true;
    }

    private static long millisSince(long start) {
        return (System.nanoTime() - start) / 1_000_000L;
    }

    /** The score a total stands for; exact inside the range {@link IncrementalScore#checkRange} holds. */
    private static HardMediumSoftScore decoded(long total) {
        long hard = hardPoints(total);
        long rest = total - hard * HARD;
        long medium = Math.floorDiv(rest + MEDIUM / 2, MEDIUM);
        return HardMediumSoftScore.of(-hard, -medium, -(rest - medium * MEDIUM));
    }

    private static long hardPoints(long delta) {
        return Math.floorDiv(delta + HARD / 2, HARD);
    }

    /** Fills the empty seats, scarcest first, each with whoever it costs least; leaves the ones that only cost. */
    private long greedy(BooleanSupplier stop, long deadline) {
        Integer[] order = new Integer[flat.nS];
        for (int s = 0; s < flat.nS; s++) {
            order[s] = s;
        }
        Arrays.sort(order, Comparator.comparingInt(s -> flat.elig[s].length));
        for (int s : order) {
            if (flat.sFixed[s] || score.assign[s] >= 0) {
                continue;
            }
            if (stop.getAsBoolean() || System.nanoTime() >= deadline) {
                break;
            }
            long bestDelta = 0;
            int bestHolder = -1;
            for (int a : flat.elig[s]) {
                score.begin();
                score.move(s, a);
                long delta = score.finish();
                score.undo();
                if (delta < bestDelta) {
                    bestDelta = delta;
                    bestHolder = a;
                }
            }
            if (bestHolder >= 0) {
                score.begin();
                score.move(s, bestHolder);
                score.finish();
            }
        }
        return score.total();
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
                || !flat.eligible[s1][a2]
                || !flat.eligible[s2][a1]) {
            return false;
        }
        score.move(s1, a2);
        score.move(s2, a1);
        return true;
    }

    /** One ejection chain per empty seat; returns the total after them. */
    private long chainEmptySeats(SplittableRandom random, BooleanSupplier stop) {
        for (int s = 0; s < flat.nS && !stop.getAsBoolean(); s++) {
            if (score.assign[s] < 0 && !flat.sFixed[s]) {
                chainBudget = CHAIN_TRIALS;
                place(s, 0, random);
            }
        }
        return score.total();
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
            if (hardPoints(score.finish()) <= -1) {
                return true;
            }
            score.undo();
            chainBudget--;
        }
        if (depth >= CHAIN_DEPTH) {
            return false;
        }
        for (int i = 0; i < candidates.length && chainBudget > 0; i++) {
            int a = candidates[(i + offset) % candidates.length];
            int[] theirs = Arrays.copyOf(score.seatsOf[a], score.countOf[a]);
            for (int y = 0; y < theirs.length && chainBudget > 0; y++) {
                int released = theirs[y];
                if (flat.sFixed[released] || Math.abs(flat.sWeek[released] - flat.sWeek[x]) > 1) {
                    continue;
                }
                score.begin();
                score.move(x, a);
                score.move(released, -1);
                chainBudget--;
                if (hardPoints(score.finish()) > 0) {
                    score.undo();
                    continue;
                }
                if (place(released, depth + 1, random)) {
                    return true;
                }
                score.begin();
                score.move(released, a);
                score.move(x, -1);
                score.finish();
            }
        }
        return false;
    }
}
