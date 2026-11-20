package dev.sylvain.planning.service.solve;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.timefold.solver.core.api.score.HardMediumSoftScore;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.solver.handsearch.HandSearch;
import dev.sylvain.planning.solver.handsearch.IncrementalProbe;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The throughput of the direct engine on a fixed amount of work, where
 * {@link SolverBenchTest} measures how far a search gets in a fixed time: two
 * versions of the engine that decide alike do exactly the same moves here, so
 * the wall time is the engine's speed and nothing else.
 *
 * <p>Runs only when {@code -Dthroughput.scenario} names a scenario — a file
 * of {@code src/main/resources/scenarios/}, or any YAML by its absolute
 * path:</p>
 * <ul>
 *   <li>{@code throughput.moves} — the draws of one measured descent of the
 *       incremental score, 2 000 000 by default;</li>
 *   <li>{@code throughput.looks} — how many times the search may look at its
 *       stop flag before it is stopped, 6 000 by default: the construction
 *       looks once a seat, the search once every 4 096 moves;</li>
 *   <li>{@code throughput.warmups} / {@code throughput.passes} — unmeasured
 *       then measured passes, 3 and 5 by default;</li>
 *   <li>{@code throughput.label} and {@code throughput.out} — a word naming
 *       the run, and the file its lines are appended to
 *       ({@code target/throughput/results.md}).</li>
 * </ul>
 *
 * <p>Each measured pass prints one line: the descent's moves a second and the
 * number its deltas fold into, then the milliseconds of the construction, the
 * moves a second of the search loop after it, and the plan it returns. The fold and the plan are the same from one version to the next
 * when it changed no decision — a line whose fold differs is not comparing
 * the same work. The search starts its ejection chain on the clock, so its
 * plan may differ between two passes once the last seats are being filled;
 * the descent never does.</p>
 */
@Tag("scenario-lent")
class HandSearchThroughputTest {

    @Test
    void measure() throws IOException {
        String scenario = System.getProperty("throughput.scenario");
        assumeTrue(scenario != null && !scenario.isBlank(), "no -Dthroughput.scenario: nothing to measure");
        int moves = Integer.getInteger("throughput.moves", 2_000_000);
        int looks = Integer.getInteger("throughput.looks", 6_000);
        int warmups = Integer.getInteger("throughput.warmups", 3);
        int passes = Integer.getInteger("throughput.passes", 5);
        String label = System.getProperty("throughput.label", "run");
        Path out = Path.of(System.getProperty("throughput.out", "target/throughput/results.md"));
        if (out.getParent() != null) {
            Files.createDirectories(out.getParent());
        }
        for (int pass = -warmups; pass < passes; pass++) {
            String line = "THROUGHPUT | " + label + " | " + Path.of(scenario).getFileName() + " | pass " + pass + " | "
                    + descent(scenario, moves) + " | " + search(scenario, looks) + " |";
            if (pass >= 0) {
                System.out.println(line);
                Files.writeString(
                        out,
                        line + System.lineSeparator(),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            }
        }
    }

    /** A seeded descent of the incremental score from a random plan: moves a second, and what its deltas fold into. */
    private static String descent(String scenario, int moves) {
        HandSearchFixtures.Loaded loaded = HandSearchFixtures.load(scenario);
        HandSearchFixtures.randomPlan(loaded.problem(), new SplittableRandom(5));
        IncrementalProbe probe = new IncrementalProbe(loaded.problem());
        long start = System.nanoTime();
        long fold = probe.descend(new SplittableRandom(7), moves);
        long nanos = System.nanoTime() - start;
        return String.format(
                Locale.ROOT,
                "descent %d moves, %.0f moves/s, fold %d",
                probe.moves(),
                probe.moves() * 1e9 / nanos,
                fold);
    }

    /** The search itself from an empty plan, stopped after a fixed number of looks at its stop flag. */
    private static String search(String scenario, int looks) {
        HandSearchFixtures.Loaded loaded = HandSearchFixtures.load(scenario);
        HandSearchFixtures.emptyPlan(loaded.problem());
        int[] looked = {0};
        long[] built = {-1, 0};
        long start = System.nanoTime();
        HardMediumSoftScore score = new HandSearch(loaded.problem())
                .solve(new HandSearch.Limits(36_000, 0, 0, 11), () -> ++looked[0] > looks, (millis, best, lay) -> {
                    // The first plan announced is the construction's: what follows is the search loop alone.
                    if (built[0] < 0) {
                        built[0] = (System.nanoTime() - start) / 1_000_000;
                        built[1] = looked[0];
                    }
                });
        long millis = (System.nanoTime() - start) / 1_000_000;
        long searched = Math.max(0, looks - built[1]) * 4_096L;
        int plan = 1;
        for (PosteAffectation poste : loaded.problem().getPostes()) {
            plan = 31 * plan
                    + (poste.getAnimateur() == null
                            ? 0
                            : poste.getAnimateur().getId().hashCode());
        }
        return String.format(
                Locale.ROOT,
                "construction %d ms, search %d moves, %.0f moves/s, %s, plan %d",
                built[0],
                searched,
                searched * 1e3 / Math.max(1, millis - built[0]),
                score,
                plan);
    }
}
