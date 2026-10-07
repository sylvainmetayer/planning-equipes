package dev.sylvain.planning.service.solve;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.timefold.solver.core.api.score.HardMediumSoftScore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Replays termination rules against a best-score curve {@link SolverBenchTest}
 * recorded, without solving again: a rule that only reads the best score over
 * time — the feasible plateau, Timefold's diminished returns, a minimum gain
 * over a window — ends at a point of that curve, and the score it would have
 * kept is the curve's value there. One fifteen-minute solve per scenario then
 * answers for every variant, with the trade-off issue-free in the open:
 * minutes saved against medium and soft given up.
 *
 * <p>Runs only with {@code -Dreplay.curve=<csv>} (one file, or several
 * separated by commas), and prints one Markdown table per curve to the
 * console and to {@code -Dreplay.out} when given. The variants are fixed
 * here: they are the ones the decision on the termination is argued with.</p>
 *
 * <p>The diminished-returns replay follows Timefold 2.7's
 * {@code DiminishedReturnsTermination} as applied in this application —
 * AND-ed with {@code bestScoreFeasible}, so it only ever acts in the second
 * local search phase, whose first step is the first feasible best: a grace
 * period of one window from there, the softest level's gain over that window
 * as the reference, then a stop as soon as the gain over the sliding window
 * falls under the reference times the ratio; a change of a harder level
 * (medium here) resets the grace period. Read on the curve at every
 * announcement and every 100 ms, which stands for the steps.</p>
 */
@Tag("scenario-lent")
class TerminationReplayTest {

    private static final long TICK_MS = 100L;

    /** Plateau lengths tried, in seconds: the current 300 and the shorter ones. */
    private static final long[] PLATEAUS = {300, 180, 120, 60};

    /** Diminished-returns windows (seconds) × ratios tried. */
    private static final long[] DR_WINDOWS = {30, 60, 120};

    private static final double[] DR_RATIOS = {0.0001, 0.01, 0.05, 0.1, 0.2};

    /** Minimum medium gain over a window (seconds): stop when the window gained less. */
    private static final long[] GAIN_WINDOWS = {60, 120, 180};

    private static final long[] GAIN_MINIMUMS = {5, 25, 100};

    @Test
    void replay() throws IOException {
        String curves = System.getProperty("replay.curve");
        assumeTrue(curves != null && !curves.isBlank(), "-Dreplay.curve names the curve(s) to replay");
        StringBuilder report = new StringBuilder();
        for (String file : curves.split(",")) {
            Path path = Path.of(file.trim());
            List<SolverBenchTest.Sample> curve = read(path);
            report.append(replay(path.getFileName().toString(), curve));
        }
        System.out.println(report);
        String out = System.getProperty("replay.out");
        if (out != null && !out.isBlank()) {
            Path target = Path.of(out);
            Files.createDirectories(target.toAbsolutePath().getParent());
            Files.writeString(target, report.toString(), StandardCharsets.UTF_8);
        }
    }

    static List<SolverBenchTest.Sample> read(Path path) throws IOException {
        List<SolverBenchTest.Sample> curve = new ArrayList<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("millis")) {
                continue;
            }
            String[] cells = line.split(",");
            curve.add(new SolverBenchTest.Sample(
                    Long.parseLong(cells[0]),
                    HardMediumSoftScore.of(
                            Long.parseLong(cells[1]), Long.parseLong(cells[2]), Long.parseLong(cells[3]))));
        }
        return curve;
    }

    static String replay(String name, List<SolverBenchTest.Sample> curve) {
        long end = curve.get(curve.size() - 1).millis();
        HardMediumSoftScore finalScore = curve.get(curve.size() - 1).score();
        long feasibleAt = feasibleAt(curve);
        StringBuilder table = new StringBuilder();
        table.append("### ").append(name).append('\n');
        table.append(String.format(
                Locale.ROOT,
                "Full run: %s, feasible at %s, final %s%n%n",
                seconds(end),
                feasibleAt < 0 ? "never" : seconds(feasibleAt),
                finalScore.toShortString()));
        table.append("| rule | stops at | saved | score kept | medium lost | soft lost |\n");
        table.append("| --- | --- | --- | --- | --- | --- |\n");
        if (feasibleAt < 0) {
            table.append("| (never feasible: no rule acts) | | | | | |\n\n");
            return table.toString();
        }
        for (long plateau : PLATEAUS) {
            row(table, "plateau " + plateau + " s", stopPlateau(curve, feasibleAt, plateau * 1000L, end), curve, end);
        }
        for (long window : DR_WINDOWS) {
            for (double ratio : DR_RATIOS) {
                row(
                        table,
                        String.format(Locale.ROOT, "diminished returns %d s, ratio %s", window, ratio),
                        stopDiminishedReturns(curve, feasibleAt, window * 1000L, ratio, end),
                        curve,
                        end);
            }
        }
        for (long window : GAIN_WINDOWS) {
            for (long minimum : GAIN_MINIMUMS) {
                row(
                        table,
                        String.format(Locale.ROOT, "medium gain < %d over %d s", minimum, window),
                        stopGain(curve, feasibleAt, window * 1000L, minimum, end),
                        curve,
                        end);
            }
        }
        table.append('\n');
        return table.toString();
    }

    private static void row(StringBuilder table, String rule, long stop, List<SolverBenchTest.Sample> curve, long end) {
        HardMediumSoftScore kept = scoreAt(curve, stop);
        HardMediumSoftScore finalScore = curve.get(curve.size() - 1).score();
        long mediumLost = finalScore.mediumScore() - kept.mediumScore();
        long softLost = finalScore.softScore() - kept.softScore();
        double mediumPct = finalScore.mediumScore() == 0 ? 0 : 100.0 * mediumLost / Math.abs(finalScore.mediumScore());
        table.append(String.format(
                Locale.ROOT,
                "| %s | %s | %s | %s | %d (%.1f %%) | %d |%n",
                rule,
                stop >= end ? seconds(end) + " (budget)" : seconds(stop),
                seconds(end - stop),
                kept.toShortString(),
                mediumLost,
                mediumPct,
                softLost));
    }

    static long feasibleAt(List<SolverBenchTest.Sample> curve) {
        for (SolverBenchTest.Sample sample : curve) {
            if (sample.score().isFeasible()) {
                return sample.millis();
            }
        }
        return -1;
    }

    static HardMediumSoftScore scoreAt(List<SolverBenchTest.Sample> curve, long millis) {
        HardMediumSoftScore latest = curve.get(0).score();
        for (SolverBenchTest.Sample sample : curve) {
            if (sample.millis() <= millis) {
                latest = sample.score();
            } else {
                break;
            }
        }
        return latest;
    }

    /** The feasible plateau: stops once feasible and unimproved for {@code plateauMs}. */
    static long stopPlateau(List<SolverBenchTest.Sample> curve, long feasibleAt, long plateauMs, long end) {
        long lastImprovement = feasibleAt;
        for (SolverBenchTest.Sample sample : curve) {
            if (sample.millis() <= feasibleAt) {
                continue;
            }
            if (sample.millis() - lastImprovement >= plateauMs) {
                return lastImprovement + plateauMs;
            }
            lastImprovement = sample.millis();
        }
        return Math.min(end, lastImprovement + plateauMs);
    }

    /** Timefold's diminished returns on the softest level, grace period and resets included. */
    static long stopDiminishedReturns(
            List<SolverBenchTest.Sample> curve, long feasibleAt, long windowMs, double ratio, long end) {
        long graceStart = feasibleAt;
        boolean graceActive = true;
        double reference = 0;
        for (long now = feasibleAt; now <= end; now += TICK_MS) {
            HardMediumSoftScore current = scoreAt(curve, now);
            if (graceActive) {
                HardMediumSoftScore first = scoreAt(curve, graceStart);
                if (harderLevelChanged(first, current)) {
                    graceStart = now;
                    continue;
                }
                if (now - graceStart >= windowMs) {
                    graceActive = false;
                    reference = current.softScore() - first.softScore();
                    if (reference == 0) {
                        return now;
                    }
                }
                continue;
            }
            HardMediumSoftScore start = scoreAt(curve, now - windowMs);
            if (harderLevelChanged(start, current)) {
                graceStart = now;
                graceActive = true;
                continue;
            }
            double gain = current.softScore() - start.softScore();
            if (gain / reference < ratio) {
                return now;
            }
        }
        return end;
    }

    private static boolean harderLevelChanged(HardMediumSoftScore start, HardMediumSoftScore current) {
        return start.hardScore() != current.hardScore() || start.mediumScore() != current.mediumScore();
    }

    /** Stops once feasible and the medium gain over the last {@code windowMs} is under {@code minimum}. */
    static long stopGain(List<SolverBenchTest.Sample> curve, long feasibleAt, long windowMs, long minimum, long end) {
        for (long now = feasibleAt + windowMs; now <= end; now += TICK_MS) {
            HardMediumSoftScore start = scoreAt(curve, now - windowMs);
            HardMediumSoftScore current = scoreAt(curve, now);
            if (current.mediumScore() - start.mediumScore() < minimum) {
                return now;
            }
        }
        return end;
    }

    private static String seconds(long millis) {
        return String.format(Locale.ROOT, "%d min %02d s", millis / 60_000L, (millis / 1000L) % 60);
    }
}
