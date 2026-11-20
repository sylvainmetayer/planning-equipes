package dev.sylvain.planning.solver.handsearch;

import ai.timefold.solver.core.api.score.HardMediumSoftScore;
import dev.sylvain.planning.domain.PlanningEvenement;
import java.util.SplittableRandom;

/**
 * The incremental score of the direct engine opened to a test of another
 * package: a walk of random moves applied and undone the way the search does
 * it, and the running total checked against a score rebuilt from the plan.
 */
public final class IncrementalProbe {

    private final FlatProblem flat;
    private final IncrementalScore score;
    private final int[] movable;
    private long total;
    private long hard;
    private long moves;

    /** Starts from the plan the problem carries. */
    public IncrementalProbe(PlanningEvenement problem) {
        this.flat = new FlatProblem(problem);
        this.score = new IncrementalScore(flat);
        int count = 0;
        int[] seats = new int[flat.nS];
        for (int s = 0; s < flat.nS; s++) {
            if (!flat.sFixed[s]) {
                seats[count++] = s;
            }
        }
        this.movable = java.util.Arrays.copyOf(seats, count);
        this.total = score.total();
        this.hard = score.totalHard();
    }

    public long moves() {
        return moves;
    }

    /**
     * {@code count} moves — a change of holder (anybody, eligible or not), a
     * swap, a seat emptied, a chain of three — a third of them undone.
     */
    public void walk(SplittableRandom random, int count) {
        if (movable.length == 0 || flat.nA == 0) {
            return;
        }
        for (int i = 0; i < count; i++) {
            score.begin();
            int kind = random.nextInt(8);
            if (kind < 3) {
                score.move(seat(random), random.nextInt(flat.nA));
            } else if (kind < 5) {
                int s1 = seat(random);
                int s2 = seat(random);
                int a1 = score.assign[s1];
                int a2 = score.assign[s2];
                if (s1 != s2) {
                    score.move(s1, a2);
                    score.move(s2, a1);
                }
            } else if (kind == 5) {
                score.move(seat(random), -1);
            } else {
                for (int link = 0; link < 3; link++) {
                    score.move(seat(random), random.nextInt(flat.nA));
                }
            }
            long delta = score.finish();
            long deltaHard = score.deltaHard;
            if (random.nextInt(3) == 0) {
                score.undo();
            } else {
                total += delta;
                hard += deltaHard;
            }
            moves++;
        }
    }

    /**
     * A seeded descent of {@code count} draws — a seat handed to somebody
     * eligible, a swap, anybody, a seat emptied — kept when it does not cost,
     * and once in sixteen anyway. Returns every delta folded into one number:
     * two engines that score alike return the same one, whatever their speed,
     * which makes this both a fixed amount of work to time and the proof that
     * the work was the same.
     */
    public long descend(SplittableRandom random, int count) {
        long fold = 17;
        for (int i = 0; i < count; i++) {
            score.begin();
            int kind = random.nextInt(8);
            int s = random.nextInt(flat.nS);
            int s2 = random.nextInt(flat.nS);
            if (!flat.sFixed[s] && !(kind >= 4 && kind < 6 && (flat.sFixed[s2] || s2 == s))) {
                if (kind < 4) {
                    int[] candidates = flat.elig[s];
                    score.move(s, candidates.length == 0 ? -1 : candidates[random.nextInt(candidates.length)]);
                } else if (kind < 6) {
                    int a1 = score.assign[s];
                    int a2 = score.assign[s2];
                    score.move(s, a2);
                    score.move(s2, a1);
                } else {
                    score.move(s, kind == 6 ? random.nextInt(flat.nA) : -1);
                }
                long delta = score.finish();
                long deltaHard = score.deltaHard;
                fold = fold * 1_000_003L + delta * 31 + deltaHard;
                if (deltaHard < 0 || (deltaHard == 0 && delta <= 0) || random.nextInt(16) == 0) {
                    total += delta;
                    hard += deltaHard;
                } else {
                    score.undo();
                }
                moves++;
            }
        }
        return fold;
    }

    private int seat(SplittableRandom random) {
        return movable[random.nextInt(movable.length)];
    }

    /**
     * What the walk left wrong, or {@code null}: the total summed move by
     * move, the one the score holds, and the one a second instance reads from
     * the plan alone must be the same three numbers.
     */
    public String drift() {
        IncrementalScore fresh = new IncrementalScore(flat);
        long freshTotal = fresh.load(score.assign.clone());
        long freshHard = fresh.totalHard();
        if (total != score.total() || hard != score.totalHard() || total != freshTotal || hard != freshHard) {
            return "summed " + hard + "/" + total + ", held " + score.totalHard() + "/" + score.total() + ", rebuilt "
                    + freshHard + "/" + freshTotal;
        }
        return null;
    }

    /** The score of the plan the walk is on, from what each move kept up to date. */
    public HardMediumSoftScore held() {
        long[] levels = score.levels();
        return HardMediumSoftScore.of(-levels[0], -levels[1], -levels[2]);
    }

    /** Lays the plan the walk is on onto the problem's movable seats. */
    public void lay() {
        for (int s : movable) {
            flat.postes.get(s).setAnimateur(score.assign[s] < 0 ? null : flat.animateurs.get(score.assign[s]));
        }
    }
}
