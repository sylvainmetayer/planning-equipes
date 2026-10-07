package dev.sylvain.planning.service.solve;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.timefold.solver.core.api.score.HardMediumSoftScore;
import ai.timefold.solver.core.api.solver.Solver;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.api.solver.event.BestSolutionChangedEvent;
import ai.timefold.solver.core.config.solver.SolverConfig;
import ai.timefold.solver.core.config.solver.termination.DiminishedReturnsTerminationConfig;
import ai.timefold.solver.core.config.solver.termination.TerminationCompositionStyle;
import ai.timefold.solver.core.config.solver.termination.TerminationConfig;
import dev.sylvain.planning.domain.ParametresQualite;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.service.EmptyReferenceData;
import dev.sylvain.planning.service.scenario.ScenarioYamlReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The solver's bench: one full solve of one scenario, under the production
 * search, with the curve of its best score written down — the measure every
 * tuning of {@code solverConfig.xml} and of the termination is argued with.
 *
 * <p>Runs only when {@code -Dbench.scenario} names a scenario — a file of
 * {@code src/main/resources/scenarios/} by its name, or any YAML by its
 * absolute path, so an edition exported from the application can be measured
 * without being committed. Everything else is a system property too:</p>
 *
 * <ul>
 *   <li>{@code bench.seconds} — the time budget, 900 by default;</li>
 *   <li>{@code bench.plateau} — the feasible-plateau bailout in seconds,
 *       300 by default, 0 for none;</li>
 *   <li>{@code bench.drWindow} / {@code bench.drRatio} — when the window is
 *       set, a diminished-returns termination replaces the plateau, gated on
 *       feasibility the same way;</li>
 *   <li>{@code bench.stopWhenFeasible} — {@code true} stops the solve at the
 *       first feasible plan, within the budget: the measure of the
 *       feasibility phase alone, as {@code solveUntilFeasible} runs it;</li>
 *   <li>{@code bench.calculations} — when set, the solve stops after that many
 *       score calculations instead of on time: two configurations that search
 *       the same way then end on the same plan, whatever their speed, which is
 *       how a rewrite of a constraint is shown to change the cost and nothing
 *       else;</li>
 *   <li>{@code bench.label} — a word naming the run in the results;</li>
 *   <li>{@code bench.out} — the Markdown file the result line is appended
 *       to, {@code target/bench/results.md} by default;</li>
 *   <li>{@code bench.jfr} — when set, a Java Flight Recorder recording
 *       (settings {@code profile}: CPU samples, allocations) of the solve
 *       alone is written to that file, to read with {@code jfr print};</li>
 *   <li>{@code bench.curve} — the CSV every announced best score is written
 *       to ({@code millis,hard,medium,soft}), {@code target/bench/<label>.csv}
 *       by default: what {@link TerminationReplayTest} replays a termination
 *       rule against without solving again.</li>
 * </ul>
 *
 * <p>Tagged {@code scenario-lent}: {@code ./mvnw test -Pscenario-tests
 * -Dtest=SolverBenchTest -Dbench.scenario=festival-hivernal.yaml}. The seed
 * is the one {@code solverConfig.xml} pins, so two runs of the same
 * configuration draw the same curve.</p>
 */
@Tag("scenario-lent")
class SolverBenchTest {

    /** Elapsed seconds at which the best score is read off the curve. */
    private static final long[] CHECKPOINTS_SECONDS = {60, 120, 180, 300, 450, 600, 900, 1200, 1800};

    /** One announcement of a new best solution: when, and what it scored. */
    record Sample(long millis, HardMediumSoftScore score) {}

    @Test
    void bench() throws IOException {
        String scenario = System.getProperty("bench.scenario");
        assumeTrue(scenario != null && !scenario.isBlank(), "-Dbench.scenario names the scenario to measure");
        long seconds = Long.getLong("bench.seconds", 900L);
        long plateau = Long.getLong("bench.plateau", 300L);
        Long drWindow = Long.getLong("bench.drWindow");
        double drRatio = Double.parseDouble(System.getProperty("bench.drRatio", "0.0001"));
        Long calculations = Long.getLong("bench.calculations");
        boolean stopWhenFeasible = Boolean.getBoolean("bench.stopWhenFeasible");
        String label = System.getProperty("bench.label", "run");
        Path out = Path.of(System.getProperty("bench.out", "target/bench/results.md"));
        Path curve = Path.of(System.getProperty("bench.curve", "target/bench/" + label + ".csv"));

        EmptyReferenceData referenceData = new EmptyReferenceData();
        SolverConfiguration configuration = new SolverConfiguration(
                seconds,
                plateau,
                ParametresQualite.EMPLACEMENTS_DISTINCTS_PAR_JOUR_MAX_PAR_DEFAUT,
                referenceData,
                ConfigProvider.getConfig());
        SolveRunner runner = new SolveRunner(configuration, referenceData, null);
        PlanningEvenement problem = load(scenario, referenceData);
        runner.prepareProblem(problem);
        FrozenPast.pin(problem.getPostes());

        SolverConfig solverConfig = configuration.solverConfigFor(new SolveBudget(seconds, plateau, null));
        if (calculations != null) {
            solverConfig.setTerminationConfig(new TerminationConfig().withScoreCalculationCountLimit(calculations));
        } else if (stopWhenFeasible) {
            solverConfig.setTerminationConfig(
                    new TerminationConfig().withSecondsSpentLimit(seconds).withBestScoreFeasible(true));
        } else if (drWindow != null) {
            solverConfig.setTerminationConfig(new TerminationConfig()
                    .withSecondsSpentLimit(seconds)
                    .withTerminationConfigList(List.of(new TerminationConfig()
                            .withBestScoreFeasible(true)
                            .withDiminishedReturnsConfig(new DiminishedReturnsTerminationConfig()
                                    .withSlidingWindowSeconds(drWindow)
                                    .withMinimumImprovementRatio(drRatio))
                            .withTerminationCompositionStyle(TerminationCompositionStyle.AND))));
        }
        SolverConfiguration.adaptToProblem(solverConfig, problem);
        Solver<PlanningEvenement> solver =
                SolverFactory.<PlanningEvenement>create(solverConfig).buildSolver();

        List<Sample> samples = new ArrayList<>();
        long[] firstInitialized = {-1};
        solver.addEventListener(event -> record(event, samples, firstInitialized));
        String jfr = System.getProperty("bench.jfr");
        long start = System.nanoTime();
        PlanningEvenement solved;
        long totalMillis;
        try (Recording recording = jfr == null ? null : new Recording(Configuration.getConfiguration("profile"))) {
            if (recording != null) {
                recording.start();
            }
            solved = solver.solve(problem);
            totalMillis = (System.nanoTime() - start) / 1_000_000L;
            if (recording != null) {
                recording.stop();
                recording.dump(Path.of(jfr));
            }
        } catch (java.text.ParseException e) {
            throw new IllegalStateException("The JFR 'profile' settings could not be read", e);
        }

        String line =
                describe(label, scenario, problem, solved, samples, firstInitialized[0], totalMillis, seconds, plateau);
        System.out.println("BENCH " + line);
        Files.createDirectories(out.toAbsolutePath().getParent());
        if (!Files.exists(out)) {
            Files.writeString(out, header(), StandardCharsets.UTF_8);
        }
        Files.writeString(out, line + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        writeCurve(curve, samples, totalMillis);
    }

    private static PlanningEvenement load(String scenario, EmptyReferenceData referenceData) throws IOException {
        Path path = Path.of(scenario);
        if (path.isAbsolute() && Files.isRegularFile(path)) {
            String yaml = Files.readString(path, StandardCharsets.UTF_8);
            return ScenarioYamlReader.buildFromScenarioText(yaml, referenceData::getParametresLegaux)
                    .planning();
        }
        return ScenarioYamlReader.buildPlanning(
                ScenarioYamlReader.readScenario(ScenarioYamlReader.scenarioPath(scenario)),
                referenceData::getParametresLegaux);
    }

    private static void record(
            BestSolutionChangedEvent<PlanningEvenement> event, List<Sample> samples, long[] firstInitialized) {
        if (!event.isNewBestSolutionInitialized() || !(event.getNewBestScore() instanceof HardMediumSoftScore score)) {
            return;
        }
        synchronized (samples) {
            if (firstInitialized[0] < 0) {
                firstInitialized[0] = event.getTimeMillisSpent();
            }
            samples.add(new Sample(event.getTimeMillisSpent(), score));
        }
    }

    /** The whole curve, one line per announcement, closed by a line at the run's end repeating the final score. */
    private static void writeCurve(Path curve, List<Sample> samples, long totalMillis) throws IOException {
        Files.createDirectories(curve.toAbsolutePath().getParent());
        StringBuilder csv = new StringBuilder("millis,hard,medium,soft\n");
        for (Sample sample : samples) {
            appendRow(csv, sample.millis(), sample.score());
        }
        if (!samples.isEmpty()) {
            appendRow(csv, totalMillis, samples.get(samples.size() - 1).score());
        }
        Files.writeString(curve, csv.toString(), StandardCharsets.UTF_8);
    }

    private static void appendRow(StringBuilder csv, long millis, HardMediumSoftScore score) {
        csv.append(millis)
                .append(',')
                .append(score.hardScore())
                .append(',')
                .append(score.mediumScore())
                .append(',')
                .append(score.softScore())
                .append('\n');
    }

    private static String header() {
        StringBuilder header = new StringBuilder("| label | scenario | seats | animateurs | budget | plateau |"
                + " CH end | feasible at | total | final");
        for (long checkpoint : CHECKPOINTS_SECONDS) {
            header.append(" | @").append(checkpoint).append("s");
        }
        header.append(" |\n|");
        int columns = 10 + CHECKPOINTS_SECONDS.length;
        header.append(" --- |".repeat(columns));
        return header.append('\n').toString();
    }

    private static String describe(
            String label,
            String scenario,
            PlanningEvenement problem,
            PlanningEvenement solved,
            List<Sample> samples,
            long firstInitialized,
            long totalMillis,
            long seconds,
            long plateau) {
        long feasibleAt = -1;
        Sample constructionEnd = null;
        for (Sample sample : samples) {
            if (constructionEnd == null) {
                constructionEnd = sample;
            }
            if (feasibleAt < 0 && sample.score().isFeasible()) {
                feasibleAt = sample.millis();
            }
        }
        StringBuilder line = new StringBuilder();
        line.append("| ")
                .append(label)
                .append(" | ")
                .append(Path.of(scenario).getFileName())
                .append(" | ")
                .append(problem.getPostes().size())
                .append(" | ")
                .append(problem.getAnimateurs().size())
                .append(" | ")
                .append(seconds)
                .append(" s | ")
                .append(plateau)
                .append(" s | ")
                .append(
                        constructionEnd == null
                                ? "—"
                                : seconds(firstInitialized) + " "
                                        + constructionEnd.score().toShortString())
                .append(" | ")
                .append(feasibleAt < 0 ? "never" : seconds(feasibleAt))
                .append(" | ")
                .append(seconds(totalMillis))
                .append(" | ")
                .append(solved.getScore() == null ? "—" : solved.getScore().toShortString());
        for (long checkpoint : CHECKPOINTS_SECONDS) {
            line.append(" | ").append(scoreAt(samples, checkpoint * 1000L, totalMillis));
        }
        return line.append(" |").toString();
    }

    /** The best score held at {@code millis}: the latest announcement before it, or a dash past the end of the run. */
    private static String scoreAt(List<Sample> samples, long millis, long totalMillis) {
        if (millis > totalMillis + 1000L) {
            return "—";
        }
        Sample latest = null;
        for (Sample sample : samples) {
            if (sample.millis() <= millis) {
                latest = sample;
            } else {
                break;
            }
        }
        return latest == null ? "—" : latest.score().toShortString();
    }

    private static String seconds(long millis) {
        return String.format(Locale.ROOT, "%.0f s", millis / 1000.0);
    }
}
