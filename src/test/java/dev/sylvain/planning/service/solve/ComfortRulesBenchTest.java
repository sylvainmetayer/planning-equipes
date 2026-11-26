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
import java.util.Set;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Measures what merging the comfort rules would cost, before anybody writes the
 * merge (Q15). Three pairs are looked at, none of which keeps today's penalties
 * exactly (see the page on the scenario tests): the two balance rules
 * ({@code equilibrerCharge}, medium, and {@code equilibrerCreneauxPenibles}, soft),
 * the two rules of the premium and exhausting stands
 * ({@code eviterRoulementStandsPremium} and {@code eviterEnchainementStandsEpuisants}),
 * and the two seat-level mismatches ({@code appreciationIncompatible} and
 * {@code experienceRequisePourStandsPremium}, the latter measured alone). Nothing
 * here touches production: the variants are rule toggles and a weight that moves a
 * rule to another level (ADR 0111).
 *
 * <p>Variants: {@code actuel}; {@code sans-penibles} and {@code sans-charge} (one of
 * the two balance rules left to do the work of both); {@code penibles-medium} (the
 * soft balance carried to the medium level, as one balance rule would); {@code
 * sans-roulement}, {@code sans-enchainement}, {@code sans-experience-premium} (the
 * rule a merge would absorb, off).</p>
 *
 * <p>Properties: {@code bench.scenario} (a name of {@code scenarios/} or an absolute
 * path, required), {@code bench.engine} ({@code hand}, the default, or
 * {@code timefold}), {@code bench.seconds} (60), {@code bench.seeds} (3),
 * {@code bench.variants} (a comma list, all by default), {@code bench.out} (a folder,
 * {@code target/bench} by default). Each solve runs the whole budget, with no
 * plateau bailout. Every final plan is scored again under the shipped rules, the
 * common ruler, and the five rules above are read off it one by one (what each costs
 * in the plan, at its own level). Output: one CSV line per solve and a Markdown line
 * per variant (medians over the seeds), in
 * {@code confort-<scenario>-<engine>.csv/.md}.</p>
 *
 * <p>Tagged {@code scenario-lent}; also launchable as a plain {@code main} on the
 * test classpath, so several solves can run in parallel JVMs.</p>
 */
@Tag("scenario-lent")
class ComfortRulesBenchTest {

    private static final String CHARGE = "equilibrerCharge";
    private static final String PENIBLES = "equilibrerCreneauxPenibles";
    private static final String ROULEMENT = "eviterRoulementStandsPremium";
    private static final String ENCHAINEMENT = "eviterEnchainementStandsEpuisants";
    private static final String EXPERIENCE = "experienceRequisePourStandsPremium";
    /** In the order of the "rules" column of the output. */
    private static final List<String> RULES = List.of(CHARGE, PENIBLES, ROULEMENT, ENCHAINEMENT, EXPERIENCE);

    private enum Variant {
        ACTUEL("actuel", Set.of(), null),
        SANS_PENIBLES("sans-penibles", Set.of(PENIBLES), null),
        SANS_CHARGE("sans-charge", Set.of(CHARGE), null),
        PENIBLES_MEDIUM("penibles-medium", Set.of(), Map.of(PENIBLES, HardMediumSoftScore.ofMedium(1))),
        SANS_ROULEMENT("sans-roulement", Set.of(ROULEMENT), null),
        SANS_ENCHAINEMENT("sans-enchainement", Set.of(ENCHAINEMENT), null),
        SANS_EXPERIENCE("sans-experience-premium", Set.of(EXPERIENCE), null);

        final String label;
        final Set<String> off;
        final Map<String, HardMediumSoftScore> weights;

        Variant(String label, Set<String> off, Map<String, HardMediumSoftScore> weights) {
            this.label = label;
            this.off = off;
            this.weights = weights;
        }
    }

    /**
     * One solve: own score, score under the shipped rules, the time to the first feasible
     * plan, and what each rule of {@link #RULES} costs in the plan under those rules.
     */
    record Result(
            Variant variant,
            long seed,
            long feasibleMillis,
            HardMediumSoftScore own,
            HardMediumSoftScore ruler,
            long[] ruleCosts) {}

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
        String stem = "confort-" + name + "-" + (timefold ? "timefold" : "hand");
        Path csv = dir.resolve(stem + ".csv");
        Files.writeString(
                csv,
                "variant,seed,feasible_ms,hard,medium,soft,ruler_hard,ruler_medium,ruler_soft,"
                        + String.join(",", RULES) + "\n",
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
                                + "," + score(result.ruler(), ",") + ","
                                + Arrays.stream(result.ruleCosts())
                                        .mapToObj(Long::toString)
                                        .reduce((a, b) -> a + "," + b)
                                        .orElse("")
                                + "\n",
                        StandardCharsets.UTF_8,
                        StandardOpenOption.APPEND);
                System.out.println("BENCH-CONFORT " + stem + " " + variant.label + " seed " + seed + " "
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

        problem.setConstraintsDesactivees(toggles(shippedToggles, variant.off));
        if (variant.weights != null) {
            Map<String, HardMediumSoftScore> overrides = new HashMap<>();
            if (shippedWeights != null) {
                shippedWeights
                        .getKnownConstraintIds()
                        .forEach(id -> overrides.put(id, shippedWeights.getConstraintWeight(id)));
            }
            overrides.putAll(variant.weights);
            problem.setPonderationsContraintes(ConstraintWeightOverrides.of(overrides));
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
        HardMediumSoftScore ruler = configuration.solutionManager().update(solved);
        // The cost of one rule in this plan: the ruler's score minus the score with that rule off.
        long[] costs = new long[RULES.size()];
        for (int i = 0; i < costs.length; i++) {
            solved.setConstraintsDesactivees(toggles(shippedToggles, Set.of(RULES.get(i))));
            HardMediumSoftScore without = configuration.solutionManager().update(solved);
            costs[i] = PENIBLES.equals(RULES.get(i))
                    ? without.softScore() - ruler.softScore()
                    : without.mediumScore() - ruler.mediumScore();
        }
        solved.setConstraintsDesactivees(shippedToggles);
        configuration.solutionManager().update(solved);
        return new Result(variant, seed, feasibleAt[0], own, ruler, costs);
    }

    /** The scenario's toggles with the rules of the bench set explicitly (off when named). */
    private static List<ConstraintToggle> toggles(List<ConstraintToggle> shipped, Set<String> off) {
        List<ConstraintToggle> toggles = new ArrayList<>(
                shipped.stream().filter(t -> !RULES.contains(t.getNom())).toList());
        RULES.forEach(rule -> toggles.add(new ConstraintToggle(rule, !off.contains(rule))));
        return toggles;
    }

    private static String summary(String scenario, String engine, Variant variant, List<Result> results) {
        long feasible = results.stream().filter(r -> r.feasibleMillis() >= 0).count();
        long rulerFeasible =
                results.stream().filter(r -> r.ruler().hardScore() == 0).count();
        StringBuilder costs = new StringBuilder();
        for (int i = 0; i < RULES.size(); i++) {
            int rule = i;
            costs.append(i == 0 ? "" : " / ")
                    .append(median(
                            results.stream().mapToLong(r -> r.ruleCosts()[rule]).toArray(), 1, "%.0f"));
        }
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
                + " | " + costs + " |\n";
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
