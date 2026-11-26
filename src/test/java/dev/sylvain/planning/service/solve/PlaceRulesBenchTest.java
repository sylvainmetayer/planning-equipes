package dev.sylvain.planning.service.solve;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
import java.util.List;
import java.util.Locale;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Measures what merging the place rules would cost, before anybody writes the
 * merge. Q19 wants two rules: « trajet impossible »
 * ({@code trajetInsuffisantEntrePostes}: speed, detour, tolerance) and
 * « dispersion sur la journée », which would absorb
 * {@code eviterChangementEmplacementEloigne} (two consecutive seats more than
 * 300 m apart) and {@code limiterEmplacementsParJour} (more than N distinct
 * locations in a day). Nothing here touches production: the variants are rule
 * toggles and the location cap of the quality parameters.
 *
 * <p>Variants:</p>
 * <ul>
 *   <li>{@code actuel} — the three rules on, what ships;</li>
 *   <li>{@code dispersion-plafond} — the 300 m rule off, the daily cap alone: the
 *       merged dispersion read as « no more than N locations », which is the
 *       cheapest form of the merge;</li>
 *   <li>{@code dispersion-plafond-serre} — the same with the cap at 2 instead of
 *       the default: the merge made stricter to make up for the distance rule it
 *       drops;</li>
 *   <li>{@code dispersion-distance} — the daily cap off, the 300 m rule alone: the
 *       other half of the merge;</li>
 *   <li>{@code sans-dispersion} — both dispersion rules off, the impossible trip
 *       alone: what the two kept rules would cost without any dispersion;</li>
 *   <li>{@code sans-trajet} — the impossible trip off, the dispersion rules on:
 *       the same level of care on the other rule.</li>
 * </ul>
 *
 * <p>Properties: {@code bench.scenario} (a name of {@code scenarios/} or an
 * absolute path, required), {@code bench.engine} ({@code hand}, the default, or
 * {@code timefold}), {@code bench.seconds} (60), {@code bench.seeds} (3),
 * {@code bench.variants} (a comma list, all by default), {@code bench.out} (a
 * folder, {@code target/bench} by default). Each solve runs the whole budget, with
 * no plateau bailout, so every variant gets the same time. Every final plan is
 * scored again under the {@code actuel} rules and cap, the common ruler. Output:
 * one CSV line per solve and a Markdown line per variant (medians over the seeds),
 * in {@code lieu-<scenario>-<engine>.csv/.md}.</p>
 *
 * <p>Tagged {@code scenario-lent}; also launchable as a plain {@code main} on
 * the test classpath, so several solves can run in parallel JVMs.</p>
 */
@Tag("scenario-lent")
class PlaceRulesBenchTest {

    private static final String CHANGE = "eviterChangementEmplacementEloigne";
    private static final String TRIP = "trajetInsuffisantEntrePostes";
    private static final String CAP = "limiterEmplacementsParJour";
    private static final int TIGHT_CAP = 2;

    private enum Variant {
        ACTUEL("actuel", true, true, true, 0),
        DISPERSION_PLAFOND("dispersion-plafond", false, true, true, 0),
        DISPERSION_PLAFOND_SERRE("dispersion-plafond-serre", false, true, true, TIGHT_CAP),
        DISPERSION_DISTANCE("dispersion-distance", true, false, true, 0),
        SANS_DISPERSION("sans-dispersion", false, false, true, 0),
        SANS_TRAJET("sans-trajet", true, true, false, 0);

        final String label;
        final boolean change;
        final boolean cap;
        final boolean trip;
        /** 0 keeps the scenario's cap. */
        final int capValue;

        Variant(String label, boolean change, boolean cap, boolean trip, int capValue) {
            this.label = label;
            this.change = change;
            this.cap = cap;
            this.trip = trip;
            this.capValue = capValue;
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
        long seconds = Long.getLong("bench.seconds", 60L);
        int seeds = Integer.getInteger("bench.seeds", 3);
        List<String> wanted = System.getProperty("bench.variants", "").isBlank()
                ? List.of()
                : Arrays.asList(System.getProperty("bench.variants").split(","));
        Path dir = Path.of(System.getProperty("bench.out", "target/bench"));
        Files.createDirectories(dir);
        String name = Path.of(scenario).getFileName().toString().replaceAll("\\.ya?ml$", "");
        String stem = "lieu-" + name + "-" + (timefold ? "timefold" : "hand");
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
                System.out.println("BENCH-LIEU " + stem + " " + variant.label + " seed " + seed + " "
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
        ParametresQualite original = problem.getParametresQualite().get(0);
        problem.setParametresQualite(List.of(withCap(original, variant.capValue)));
        FrozenPast.pin(problem.getPostes());
        problem.setConstraintsDesactivees(toggles(problem, variant));

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
        solved.setConstraintsDesactivees(toggles(solved, Variant.ACTUEL));
        solved.setParametresQualite(List.of(original));
        return new Result(
                variant,
                seed,
                feasibleAt[0],
                own,
                configuration.solutionManager().update(solved));
    }

    private static ParametresQualite withCap(ParametresQualite q, int cap) {
        if (cap == 0) {
            return q;
        }
        return new ParametresQualite(
                cap,
                q.heureServiceTardif(),
                q.heureServiceMatinal(),
                q.reposSouhaiteApresServiceTardifMinutes(),
                q.typologiesDistinctesMax(),
                q.joursConsecutifsMax(),
                q.vitesseMarcheKmH(),
                q.facteurDetour(),
                q.toleranceTrajetMinutes(),
                q.toleranceArriveeGroupeeMinutes(),
                q.modifieLe());
    }

    /** The scenario's toggles with the three place rules set explicitly. */
    private static List<ConstraintToggle> toggles(PlanningEvenement problem, Variant variant) {
        List<ConstraintToggle> toggles = new ArrayList<>(problem.getConstraintsDesactivees().stream()
                .filter(t -> !CHANGE.equals(t.getNom()) && !TRIP.equals(t.getNom()) && !CAP.equals(t.getNom()))
                .toList());
        toggles.add(new ConstraintToggle(CHANGE, variant.change));
        toggles.add(new ConstraintToggle(TRIP, variant.trip));
        toggles.add(new ConstraintToggle(CAP, variant.cap));
        return toggles;
    }

    private static String summary(String scenario, String engine, Variant variant, List<Result> results) {
        long feasible = results.stream().filter(r -> r.feasibleMillis() >= 0).count();
        long rulerFeasible =
                results.stream().filter(r -> r.ruler().hardScore() == 0).count();
        return "| `" + scenario + "`" + " | " + engine + " | `" + variant.label + "` | " + feasible + "/"
                + results.size()
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
