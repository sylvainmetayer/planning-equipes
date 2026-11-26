package dev.sylvain.planning.service.solve;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.timefold.solver.core.api.domain.solution.ConstraintWeightOverrides;
import ai.timefold.solver.core.api.score.HardMediumSoftScore;
import ai.timefold.solver.core.api.solver.Solver;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.solver.SolverConfig;
import dev.sylvain.planning.domain.ConstraintToggle;
import dev.sylvain.planning.domain.ParametresQualite;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.service.EmptyReferenceData;
import dev.sylvain.planning.solver.PosteAffectationDifficultyComparatorFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Measures what merging {@code maxJoursConsecutifsTravailles} (medium) and
 * {@code maxJoursConsecutifsTravaillesDur} (hard) into one rule of adjustable
 * level would cost, and what removing the calendar construction order of
 * ADR 0068 would cost — before anybody writes either. Nothing here changes a
 * behaviour: the variants are toggles, a weight that moves the medium rule to
 * the hard level (ADR 0111) and one test seam on the comparator.
 *
 * <p>Variants (the cap is {@code bench.cap}, 6 by default):</p>
 * <ul>
 *   <li>{@code preference} — the medium form alone: what ships, and what the
 *       merged rule would be at the preference level;</li>
 *   <li>{@code both} — both forms on, as an organiser who wants a hard cap runs
 *       them today (the calendar order applies under Timefold);</li>
 *   <li>{@code hard} — the hard form alone, calendar order applying under
 *       Timefold: the merged rule at the mandatory level, as built today;</li>
 *   <li>{@code both-sans-calendrier}, {@code hard-sans-calendrier} — the same
 *       without the calendar order (Timefold only: the direct engine builds by
 *       scarcity alone, so removing the order changes nothing there);</li>
 *   <li>{@code hard-par-poids} — the medium form alone, its weight turned into
 *       one hard point: the merged rule as ADR 0111 would write it, which
 *       carries no calendar order since nothing tells the comparator.</li>
 * </ul>
 *
 * <p>Properties: {@code bench.scenario} (a name of {@code scenarios/} or an
 * absolute path, required), {@code bench.engine} ({@code hand}, the default, or
 * {@code timefold}), {@code bench.seconds} (120), {@code bench.seeds} (3),
 * {@code bench.cap} (6), {@code bench.variants} (a comma list, all by default),
 * {@code bench.out} (a folder, {@code target/bench} by default). Each solve
 * runs the whole budget, with no plateau bailout, so every variant gets the same
 * time. Every final plan is scored again under the {@code both} rules, the common
 * ruler: its hard is the days beyond the cap, its medium the cost everyone pays.
 * Output: one CSV line per solve and a Markdown line per variant (medians over the
 * seeds), in {@code jours-affilee-<scenario>-<engine>.csv/.md}.</p>
 *
 * <p>Tagged {@code scenario-lent}; also launchable as a plain {@code main} on
 * the test classpath, so several solves can run in parallel JVMs.</p>
 */
@Tag("scenario-lent")
class ConsecutiveRunsBenchTest {

    private static final String SOFT_FORM = "maxJoursConsecutifsTravailles";
    private static final String HARD_FORM = "maxJoursConsecutifsTravaillesDur";

    private enum Variant {
        PREFERENCE("preference", true, false, false, false, true),
        BOTH("both", true, true, false, false, true),
        BOTH_NO_CALENDAR("both-sans-calendrier", true, true, false, true, false),
        HARD("hard", false, true, false, false, true),
        HARD_NO_CALENDAR("hard-sans-calendrier", false, true, false, true, false),
        HARD_BY_WEIGHT("hard-par-poids", true, false, true, false, true);

        final String label;
        final boolean soft;
        final boolean hard;
        final boolean softAsHard;
        final boolean noCalendar;
        final boolean onHandEngine;

        Variant(
                String label,
                boolean soft,
                boolean hard,
                boolean softAsHard,
                boolean noCalendar,
                boolean onHandEngine) {
            this.label = label;
            this.soft = soft;
            this.hard = hard;
            this.softAsHard = softAsHard;
            this.noCalendar = noCalendar;
            this.onHandEngine = onHandEngine;
        }
    }

    /** One solve: own score, score under the common ruler, and the time to the first feasible plan. */
    record Result(
            Variant variant, long seed, long feasibleMillis, HardMediumSoftScore own, HardMediumSoftScore ruler) {}

    @Test
    void bench() throws IOException {
        assumeTrue(System.getProperty("bench.scenario") != null, "-Dbench.scenario names the scenario to measure");
        main(new String[0]);
    }

    public static void main(String[] args) throws IOException {
        String scenario = System.getProperty("bench.scenario");
        boolean timefold = "timefold".equals(System.getProperty("bench.engine"));
        long seconds = Long.getLong("bench.seconds", 120L);
        int seeds = Integer.getInteger("bench.seeds", 3);
        int cap = Integer.getInteger("bench.cap", 6);
        List<String> wanted = System.getProperty("bench.variants", "").isBlank()
                ? List.of()
                : Arrays.asList(System.getProperty("bench.variants").split(","));
        Path dir = Path.of(System.getProperty("bench.out", "target/bench"));
        Files.createDirectories(dir);
        String name = Path.of(scenario).getFileName().toString().replaceAll("\\.ya?ml$", "");
        String stem = "jours-affilee-" + name + "-" + (timefold ? "timefold" : "hand");
        Path csv = dir.resolve(stem + ".csv");
        Files.writeString(
                csv,
                "variant,seed,feasible_ms,hard,medium,soft,ruler_hard,ruler_medium,ruler_soft\n",
                StandardCharsets.UTF_8);
        StringBuilder md = new StringBuilder();
        try {
            for (Variant variant : Variant.values()) {
                if ((!timefold && !variant.onHandEngine) || (!wanted.isEmpty() && !wanted.contains(variant.label))) {
                    continue;
                }
                List<Result> results = new ArrayList<>();
                for (long seed = 0; seed < seeds; seed++) {
                    Result result = solve(scenario, timefold, variant, seed, seconds, cap);
                    results.add(result);
                    Files.writeString(
                            csv,
                            variant.label + "," + seed + "," + result.feasibleMillis() + "," + score(result.own(), ",")
                                    + "," + score(result.ruler(), ",") + "\n",
                            StandardCharsets.UTF_8,
                            StandardOpenOption.APPEND);
                    System.out.println("BENCH-AFFILEE " + stem + " " + variant.label + " seed " + seed + " "
                            + result.own().toShortString());
                }
                md.append(summary(name, timefold ? "Timefold" : "direct", variant, results));
            }
        } finally {
            // The calendar order is a JVM-wide seam: always put back.
            PosteAffectationDifficultyComparatorFactory.calendarOrderUnderHardRunCap = true;
        }
        Files.writeString(dir.resolve(stem + ".md"), md, StandardCharsets.UTF_8);
        System.out.print(md);
    }

    private static Result solve(String scenario, boolean timefold, Variant variant, long seed, long seconds, int cap)
            throws IOException {
        EmptyReferenceData referenceData = new EmptyReferenceData();
        SolverConfiguration configuration = new SolverConfiguration(
                seconds,
                0L,
                ParametresQualite.EMPLACEMENTS_DISTINCTS_PAR_JOUR_MAX_PAR_DEFAUT,
                referenceData,
                ConfigProvider.getConfig());
        SolveRunner runner = new SolveRunner(configuration, referenceData, null);
        SolverBenchTest.Loaded loaded = SolverBenchTest.load(scenario, referenceData);
        PlanningEvenement problem = loaded.problem();
        runner.prepareProblem(problem);
        if (loaded.qualite() != null) {
            problem.setParametresQualite(List.of(loaded.qualite()));
        }
        ParametresQualite q = problem.getParametresQualite().get(0);
        problem.setParametresQualite(List.of(new ParametresQualite(
                q.maxEmplacementsDistinctsParJour(),
                q.heureServiceTardif(),
                q.heureServiceMatinal(),
                q.reposSouhaiteApresServiceTardifMinutes(),
                q.typologiesDistinctesMax(),
                cap,
                q.vitesseMarcheKmH(),
                q.facteurDetour(),
                q.toleranceTrajetMinutes(),
                q.toleranceArriveeGroupeeMinutes(),
                q.modifieLe())));
        FrozenPast.pin(problem.getPostes());
        ConstraintWeightOverrides<HardMediumSoftScore> weightsAsStand = problem.getPonderationsContraintes();
        problem.setConstraintsDesactivees(toggles(problem, variant.soft, variant.hard));
        if (variant.softAsHard) {
            Map<String, HardMediumSoftScore> overrides = new HashMap<>();
            if (weightsAsStand != null) {
                weightsAsStand
                        .getKnownConstraintIds()
                        .forEach(id -> overrides.put(id, weightsAsStand.getConstraintWeight(id)));
            }
            overrides.put(SOFT_FORM, HardMediumSoftScore.ofHard(1));
            problem.setPonderationsContraintes(ConstraintWeightOverrides.of(overrides));
        }
        PosteAffectationDifficultyComparatorFactory.calendarOrderUnderHardRunCap = !variant.noCalendar;

        Solver<PlanningEvenement> solver;
        if (timefold) {
            SolverConfig solverConfig = configuration.solverConfigFor(new SolveBudget(seconds, 0L), problem);
            solverConfig.setRandomSeed(seed);
            SolverConfiguration.adaptToProblem(solverConfig, problem);
            solver = SolverFactory.<PlanningEvenement>create(solverConfig).buildSolver();
        } else {
            solver = new HandSearchSolver(
                    new SolverConfiguration.SearchSettings(seconds, 0L, 0, SolverEngine.HAND),
                    configuration.plateauGainMedium(problem),
                    configuration.solutionManager(),
                    seed);
        }
        long[] feasibleAt = {-1};
        solver.addEventListener(event -> {
            if (feasibleAt[0] < 0
                    && event.isNewBestSolutionInitialized()
                    && event.getNewBestScore() instanceof HardMediumSoftScore score
                    && score.isFeasible()) {
                feasibleAt[0] = event.getTimeMillisSpent();
            }
        });
        PlanningEvenement solved = solver.solve(problem);
        HardMediumSoftScore own = solved.getScore();
        solved.setConstraintsDesactivees(toggles(solved, true, true));
        solved.setPonderationsContraintes(weightsAsStand);
        return new Result(
                variant,
                seed,
                feasibleAt[0],
                own,
                configuration.solutionManager().update(solved));
    }

    /** The scenario's toggles with the two forms of the rule set explicitly. */
    private static List<ConstraintToggle> toggles(PlanningEvenement problem, boolean soft, boolean hard) {
        List<ConstraintToggle> toggles = new ArrayList<>(problem.getConstraintsDesactivees().stream()
                .filter(t -> !SOFT_FORM.equals(t.getNom()) && !HARD_FORM.equals(t.getNom()))
                .toList());
        toggles.add(new ConstraintToggle(SOFT_FORM, soft));
        toggles.add(new ConstraintToggle(HARD_FORM, hard));
        return toggles;
    }

    private static String summary(String scenario, String engine, Variant variant, List<Result> results) {
        long feasible = results.stream().filter(r -> r.feasibleMillis() >= 0).count();
        long rulerFeasible =
                results.stream().filter(r -> r.ruler().hardScore() == 0).count();
        return "| " + scenario + " | " + engine + " | " + variant.label + " | " + feasible + "/" + results.size()
                + " | "
                + median(
                        results.stream()
                                .filter(r -> r.feasibleMillis() >= 0)
                                .mapToLong(Result::feasibleMillis)
                                .toArray(),
                        1000.0,
                        "%.1f s")
                + " | "
                + median(results.stream().mapToLong(r -> r.own().hardScore()).toArray(), 1, "%.0f")
                + " | "
                + median(results.stream().mapToLong(r -> r.own().mediumScore()).toArray(), 1, "%.0f")
                + " | "
                + median(results.stream().mapToLong(r -> r.own().softScore()).toArray(), 1, "%.0f")
                + " | " + rulerFeasible + "/" + results.size()
                + " | "
                + median(
                        results.stream().mapToLong(r -> r.ruler().mediumScore()).toArray(), 1, "%.0f")
                + " |\n";
    }

    private static String median(long[] values, double divisor, String format) {
        if (values.length == 0) {
            return "jamais";
        }
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        return String.format(Locale.ROOT, format, sorted[sorted.length / 2] / divisor);
    }

    private static String score(HardMediumSoftScore s, String sep) {
        return s.hardScore() + sep + s.mediumScore() + sep + s.softScore();
    }
}
