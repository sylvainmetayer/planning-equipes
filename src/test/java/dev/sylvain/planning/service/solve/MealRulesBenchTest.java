package dev.sylvain.planning.service.solve;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.timefold.solver.core.api.domain.solution.ConstraintWeightOverrides;
import ai.timefold.solver.core.api.score.HardMediumSoftScore;
import ai.timefold.solver.core.api.solver.Solver;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.solver.SolverConfig;
import dev.sylvain.planning.domain.ConstraintToggle;
import dev.sylvain.planning.domain.FenetreRepas;
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
 * Measures what merging the two meal rules, {@code coupureRepasObligatoire}
 * (the break owed, hard) and {@code coupureRepasPlacementPrefere} (where in the
 * window it is taken, soft), into one rule whose preferred placement is a
 * parameter would cost, before anybody writes it. Nothing here changes a
 * behaviour, and no production seam is needed: the variants are toggles, weights
 * that move a rule to another level (ADR 0111) and a rewrite of the
 * {@link FenetreRepas} facts of the problem, whose {@code auPlusTard} flag is the
 * placement the merged rule would read as a parameter.
 *
 * <p>Variants:</p>
 * <ul>
 *   <li>{@code actuel} — what ships: the break hard, the placement soft;</li>
 *   <li>{@code fusion-dur} — one rule at the mandatory level: the placement gap
 *       counts one hard point per minute, like the missing break;</li>
 *   <li>{@code fusion-preference} — one rule at the preference level: break and
 *       placement both one medium point of weight 5 per minute;</li>
 *   <li>{@code sans-placement} — the parameter set to « no preference »: the
 *       break alone;</li>
 *   <li>{@code placement-debut}, {@code placement-fin} — the parameter set to
 *       the start, or the end, of every window (the shipped one is the end at
 *       midday and the start in the evening), the placement rule soft.</li>
 * </ul>
 *
 * <p>Properties: {@code bench.scenario} (a name of {@code scenarios/} or an
 * absolute path, required), {@code bench.engine} ({@code hand}, the default, or
 * {@code timefold}), {@code bench.seconds} (60), {@code bench.seeds} (3),
 * {@code bench.variants} (a comma list, all by default), {@code bench.out} (a
 * folder, {@code target/bench} by default). Each solve runs the whole budget,
 * with no plateau bailout. Every final plan is scored again under the shipped
 * rules, the common ruler. Output: one CSV line per solve and a Markdown line
 * per variant (medians over the seeds), in {@code repas-<scenario>-<engine>.csv/.md}.</p>
 *
 * <p>Tagged {@code scenario-lent}; also launchable as a plain {@code main} on
 * the test classpath, so several solves can run in parallel JVMs.</p>
 */
@Tag("scenario-lent")
class MealRulesBenchTest {

    private static final String BREAK = "coupureRepasObligatoire";
    private static final String PLACEMENT = "coupureRepasPlacementPrefere";

    private enum Variant {
        ACTUEL("actuel", true, null, null),
        FUSION_HARD("fusion-dur", true, HardMediumSoftScore.ofHard(1), HardMediumSoftScore.ofHard(1)),
        FUSION_PREFERENCE("fusion-preference", true, HardMediumSoftScore.ofMedium(5), HardMediumSoftScore.ofMedium(5)),
        NO_PLACEMENT("sans-placement", false, null, null),
        PLACEMENT_START("placement-debut", true, null, null),
        PLACEMENT_END("placement-fin", true, null, null);

        final String label;
        final boolean placementOn;
        final HardMediumSoftScore breakWeight;
        final HardMediumSoftScore placementWeight;

        Variant(
                String label,
                boolean placementOn,
                HardMediumSoftScore breakWeight,
                HardMediumSoftScore placementWeight) {
            this.label = label;
            this.placementOn = placementOn;
            this.breakWeight = breakWeight;
            this.placementWeight = placementWeight;
        }
    }

    /** One solve: own score, score under the shipped rules, and the time to the first feasible plan. */
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
        long seconds = Long.getLong("bench.seconds", 60L);
        int seeds = Integer.getInteger("bench.seeds", 3);
        List<String> wanted = System.getProperty("bench.variants", "").isBlank()
                ? List.of()
                : Arrays.asList(System.getProperty("bench.variants").split(","));
        List<String> known = Arrays.stream(Variant.values()).map(v -> v.label).toList();
        if (!known.containsAll(wanted)) {
            throw new IllegalArgumentException("bench.variants must name variants among " + known + ", got " + wanted);
        }
        Path dir = Path.of(System.getProperty("bench.out", "target/bench"));
        Files.createDirectories(dir);
        String name = Path.of(scenario).getFileName().toString().replaceAll("\\.ya?ml$", "");
        String stem = "repas-" + name + "-" + (timefold ? "timefold" : "hand");
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
                Result result = solve(scenario, timefold, variant, seed, seconds);
                results.add(result);
                Files.writeString(
                        csv,
                        variant.label + "," + seed + "," + result.feasibleMillis() + "," + score(result.own(), ",")
                                + "," + score(result.ruler(), ",") + "\n",
                        StandardCharsets.UTF_8,
                        StandardOpenOption.APPEND);
                System.out.println("BENCH-REPAS " + stem + " " + variant.label + " seed " + seed + " "
                        + result.own().toShortString());
            }
            md.append(summary(name, timefold ? "Timefold" : "direct", variant, results));
        }
        Files.writeString(dir.resolve(stem + ".md"), md, StandardCharsets.UTF_8);
        System.out.print(md);
    }

    private static Result solve(String scenario, boolean timefold, Variant variant, long seed, long seconds)
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
        FrozenPast.pin(problem.getPostes());
        // What the ruler puts back once the solve is over.
        ConstraintWeightOverrides<HardMediumSoftScore> shippedWeights = problem.getPonderationsContraintes();
        List<ConstraintToggle> shippedToggles = List.copyOf(problem.getConstraintsDesactivees());
        List<FenetreRepas> shippedWindows = List.copyOf(problem.getFenetresRepas());

        problem.setConstraintsDesactivees(toggles(shippedToggles, variant.placementOn));
        if (variant.breakWeight != null) {
            Map<String, HardMediumSoftScore> overrides = new HashMap<>();
            if (shippedWeights != null) {
                shippedWeights
                        .getKnownConstraintIds()
                        .forEach(id -> overrides.put(id, shippedWeights.getConstraintWeight(id)));
            }
            overrides.put(BREAK, variant.breakWeight);
            overrides.put(PLACEMENT, variant.placementWeight);
            problem.setPonderationsContraintes(ConstraintWeightOverrides.of(overrides));
        }
        if (variant == Variant.PLACEMENT_START || variant == Variant.PLACEMENT_END) {
            problem.setFenetresRepas(shippedWindows.stream()
                    .map(w -> new FenetreRepas(
                            w.libelle(),
                            w.debut(),
                            w.fin(),
                            w.dureeMinutes(),
                            variant == Variant.PLACEMENT_END,
                            w.date(),
                            w.datesExclues()))
                    .toList());
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
        solved.setConstraintsDesactivees(shippedToggles);
        solved.setPonderationsContraintes(shippedWeights);
        solved.setFenetresRepas(shippedWindows);
        return new Result(
                variant,
                seed,
                feasibleAt[0],
                own,
                configuration.solutionManager().update(solved));
    }

    /** The scenario's toggles with the two meal rules set explicitly. */
    private static List<ConstraintToggle> toggles(List<ConstraintToggle> shipped, boolean placementOn) {
        List<ConstraintToggle> toggles = new ArrayList<>(shipped.stream()
                .filter(t -> !BREAK.equals(t.getNom()) && !PLACEMENT.equals(t.getNom()))
                .toList());
        toggles.add(new ConstraintToggle(BREAK, true));
        toggles.add(new ConstraintToggle(PLACEMENT, placementOn));
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
                + " | "
                + median(results.stream().mapToLong(r -> r.ruler().softScore()).toArray(), 1, "%.0f")
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
