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
 * Measures the run-of-days rule at its two levels (ADR 0118): {@code preference},
 * what ships, and {@code obligatoire}, the rule's weight turned into one hard
 * point (ADR 0111). Written before the merge of its two 3.x forms to price it
 * (the figures of 26 November 2026 in {@code tests-de-scenarios.md}); kept to
 * measure the merged rule at either level.
 *
 * <p>Properties: {@code bench.scenario} (a name of {@code scenarios/} or an
 * absolute path, required), {@code bench.engine} ({@code hand}, the default, or
 * {@code timefold}), {@code bench.seconds} (120), {@code bench.seeds} (3),
 * {@code bench.cap} (6), {@code bench.variants} (a comma list, all by default),
 * {@code bench.out} (a folder, {@code target/bench} by default). Each solve
 * runs the whole budget, with no plateau bailout, so every variant gets the same
 * time. Every final plan is scored again with the rule obligatoire, the common
 * ruler: its hard is the days beyond the cap, its medium the cost everyone pays.
 * Output: one CSV line per solve and a Markdown line per variant (medians over the
 * seeds), in {@code jours-affilee-<scenario>-<engine>.csv/.md}.</p>
 *
 * <p>Tagged {@code scenario-lent}; also launchable as a plain {@code main} on
 * the test classpath, so several solves can run in parallel JVMs.</p>
 */
@Tag("scenario-lent")
class ConsecutiveRunsBenchTest {

    private static final String RULE = "maxJoursConsecutifsTravailles";

    private enum Variant {
        PREFERENCE("preference", false),
        OBLIGATOIRE("obligatoire", true);

        final String label;
        final boolean hard;

        Variant(String label, boolean hard) {
            this.label = label;
            this.hard = hard;
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
        for (Variant variant : Variant.values()) {
            if (!wanted.isEmpty() && !wanted.contains(variant.label)) {
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
        problem.setConstraintsDesactivees(withRuleOn(problem));
        if (variant.hard) {
            problem.setPonderationsContraintes(obligatoire(weightsAsStand));
        }

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
        solved.setPonderationsContraintes(obligatoire(weightsAsStand));
        return new Result(
                variant,
                seed,
                feasibleAt[0],
                own,
                configuration.solutionManager().update(solved));
    }

    /** The scenario's toggles, with the rule on. */
    private static List<ConstraintToggle> withRuleOn(PlanningEvenement problem) {
        List<ConstraintToggle> toggles = new ArrayList<>(problem.getConstraintsDesactivees().stream()
                .filter(t -> !RULE.equals(t.getNom()))
                .toList());
        toggles.add(new ConstraintToggle(RULE, true));
        return toggles;
    }

    /** The weights the scenario stands with, the rule's turned into one hard point. */
    private static ConstraintWeightOverrides<HardMediumSoftScore> obligatoire(
            ConstraintWeightOverrides<HardMediumSoftScore> weights) {
        Map<String, HardMediumSoftScore> overrides = new HashMap<>();
        if (weights != null) {
            weights.getKnownConstraintIds().forEach(id -> overrides.put(id, weights.getConstraintWeight(id)));
        }
        overrides.put(RULE, HardMediumSoftScore.ofHard(1));
        return ConstraintWeightOverrides.of(overrides);
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
