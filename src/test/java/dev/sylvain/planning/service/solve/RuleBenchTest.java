package dev.sylvain.planning.service.solve;

import static org.junit.jupiter.api.Assertions.assertTrue;
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
import dev.sylvain.planning.service.scenario.ScenariosLivres;
import dev.sylvain.planning.solver.ConstraintCatalog;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The rule-by-rule bench: what a change of the rules costs, scenario by
 * scenario, before anybody writes it. Each scenario is solved twice under the
 * same budget and seed — as it stands, then with the proposed variant — and
 * the two plans are compared: score per level, time to feasibility, seats that
 * changed hands, and a verdict against the decision threshold (at most 5 % of
 * medium lost).
 *
 * <p>The variant is a system property, at least one of:</p>
 * <ul>
 *   <li>{@code bench.off=ruleA,ruleB} — rules switched off: a rule proposed
 *       for removal, or the rules a proposed merge would replace;</li>
 *   <li>{@code bench.level=rule:HARD|MEDIUM|SOFT[:weight],...} — a rule moved
 *       to another level, and optionally given a weight there.</li>
 * </ul>
 *
 * <p>The scenarios: {@code gamme-*} (the infeasible rungs left out) and
 * {@code festival-*} by default; {@code bench.only=gamme-01,gamme-02} keeps
 * the shipped files whose name starts so; {@code bench.scenario=<absolute
 * path>} adds a local file, read in place and never copied. Budget:
 * {@code bench.seconds} (60) and {@code bench.plateau} (30) per solve,
 * {@code bench.seed} (0, for either engine), {@code bench.engine=timefold} for Timefold's search
 * in place of the direct engine, {@code bench.label} (the file names).</p>
 *
 * <p>The variant's plan is scored again <b>under the rules as they stand</b>
 * (the "re-scored" columns): a plan solved without a rule scores better by its
 * own rules than it deserves, and the verdict is read on the re-scored
 * medium, which is the loss the operator would actually see. A hard score
 * worse than the baseline's always fails the verdict. Seats changed are
 * counted on the natural key (stand, day, hours), never on the poste id; the
 * pure half lives in {@link RuleBenchMeasure}. Written to {@code target/bench/regles-<label>.md}
 * and {@code .csv}.</p>
 */
@Tag("scenario-lent")
class RuleBenchTest {

    /** One solve of one scenario, as the bench measures it. */
    record Run(
            HardMediumSoftScore ownScore,
            HardMediumSoftScore rescored,
            long feasibleMillis,
            long totalMillis,
            PlanningEvenement plan) {}

    @Test
    void bench() throws IOException {
        String off = System.getProperty("bench.off", "");
        String level = System.getProperty("bench.level", "");
        assumeTrue(!off.isBlank() || !level.isBlank(), "-Dbench.off and/or -Dbench.level describe the variant");
        String label = System.getProperty("bench.label", "variante");
        List<String> offRules = split(off);
        Map<String, HardMediumSoftScore> levels = parseLevels(level);
        offRules.forEach(RuleBenchTest::requireKnown);
        levels.keySet().forEach(RuleBenchTest::requireKnown);

        List<String> scenarios = scenarios();
        StringBuilder md = new StringBuilder("# Banc règle par règle — " + label + "\n\n")
                .append("Variante : désactivées=")
                .append(offRules)
                .append(" niveaux=")
                .append(levels.keySet())
                .append(" — seuil de medium perdu : ")
                .append(percent(RuleBenchMeasure.MEDIUM_LOSS_THRESHOLD))
                .append("\n\n| scénario | sièges | avant (h/m/s) | après (h/m/s) | après, re-scoré (h/m/s) |"
                        + " faisable avant | faisable après | sièges changés | medium perdu | verdict |\n"
                        + "| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |\n");
        StringBuilder csv = new StringBuilder("scenario,seats,before_hard,before_medium,before_soft,after_hard,"
                + "after_medium,after_soft,rescored_hard,rescored_medium,rescored_soft,feasible_before_ms,"
                + "feasible_after_ms,seats_changed,medium_lost,verdict\n");
        List<String> errors = new ArrayList<>();
        for (String scenario : scenarios) {
            String name = Path.of(scenario).getFileName().toString();
            try {
                Run before = run(scenario, List.of(), Map.of());
                Run after = run(scenario, offRules, levels);
                int changed = RuleBenchMeasure.changedSeats(before.plan(), after.plan());
                double lost = RuleBenchMeasure.mediumLost(before.ownScore(), after.rescored());
                String verdict = RuleBenchMeasure.verdict(before.ownScore(), after.rescored());
                md.append(row(name, before, after, changed, lost, verdict));
                csv.append(csvRow(name, before, after, changed, lost, verdict));
            } catch (RuntimeException e) {
                // One scenario failing (the referee included) must not hide the others.
                md.append("| ").append(name).append(" | ERREUR : ").append(e).append(" |\n");
                errors.add(name + ": " + e);
                csv.append(name).append(",,,,,,,,,,,,,,,ERREUR\n");
            }
            System.out.println("BENCH-REGLES " + name + " termine");
        }
        Path dir = Path.of("target/bench");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("regles-" + label + ".md"), md, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("regles-" + label + ".csv"), csv, StandardCharsets.UTF_8);
        System.out.println(md);
        // Written first, then loud: a referee that disagreed is a fault, not a row to skim past.
        assertTrue(errors.isEmpty(), () -> "Scenarios in error: " + errors);
    }

    /**
     * One solve. With no variant, the plan as it stands; otherwise the plan is
     * solved under the variant, then scored again under the rules as they
     * stand, by the same solution manager the direct engine is refereed with.
     */
    private static Run run(String scenario, List<String> off, Map<String, HardMediumSoftScore> levels)
            throws IOException {
        boolean variant = !off.isEmpty() || !levels.isEmpty();
        long seconds = Long.getLong("bench.seconds", 60L);
        long plateau = Long.getLong("bench.plateau", 30L);
        long seed = Long.getLong("bench.seed", 0L);
        EmptyReferenceData referenceData = new EmptyReferenceData();
        SolverConfiguration configuration = new SolverConfiguration(
                seconds,
                plateau,
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
        List<ConstraintToggle> asStands = List.copyOf(problem.getConstraintsDesactivees());
        ConstraintWeightOverrides<HardMediumSoftScore> weightsAsStand = problem.getPonderationsContraintes();
        applyVariant(problem, off, levels);

        Solver<PlanningEvenement> solver;
        if ("timefold".equals(System.getProperty("bench.engine"))) {
            // The seed solverConfig.xml pins would otherwise ignore bench.seed.
            SolverConfig solverConfig = configuration.solverConfigFor(new SolveBudget(seconds, plateau, null), problem);
            solverConfig.setRandomSeed(seed);
            SolverConfiguration.adaptToProblem(solverConfig, problem);
            solver = SolverFactory.<PlanningEvenement>create(solverConfig).buildSolver();
        } else {
            solver = new HandSearchSolver(
                    new SolverConfiguration.SearchSettings(seconds, plateau, 0, SolverEngine.HAND),
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
        long start = System.nanoTime();
        PlanningEvenement solved = solver.solve(problem);
        long total = (System.nanoTime() - start) / 1_000_000L;
        HardMediumSoftScore own = solved.getScore();
        HardMediumSoftScore rescored = own;
        if (variant) {
            solved.setConstraintsDesactivees(asStands);
            solved.setPonderationsContraintes(weightsAsStand);
            rescored = configuration.solutionManager().update(solved);
        }
        return new Run(own, rescored, feasibleAt[0], total, solved);
    }

    private static void applyVariant(
            PlanningEvenement problem, List<String> off, Map<String, HardMediumSoftScore> levels) {
        if (!off.isEmpty()) {
            List<ConstraintToggle> toggles = new ArrayList<>(problem.getConstraintsDesactivees().stream()
                    .filter(toggle -> !off.contains(toggle.getNom()))
                    .toList());
            off.forEach(rule -> toggles.add(new ConstraintToggle(rule, false)));
            problem.setConstraintsDesactivees(toggles);
        }
        if (!levels.isEmpty()) {
            Map<String, HardMediumSoftScore> overrides = new HashMap<>();
            ConstraintWeightOverrides<HardMediumSoftScore> current = problem.getPonderationsContraintes();
            if (current != null) {
                current.getKnownConstraintIds().forEach(id -> overrides.put(id, current.getConstraintWeight(id)));
            }
            overrides.putAll(levels);
            problem.setPonderationsContraintes(ConstraintWeightOverrides.of(overrides));
        }
    }

    private static String row(String name, Run before, Run after, int changed, double lost, String verdict) {
        return "| " + name + " | " + before.plan().getPostes().size() + " | " + levels(before.ownScore()) + " | "
                + levels(after.ownScore()) + " | " + levels(after.rescored()) + " | " + seconds(before.feasibleMillis())
                + " | " + seconds(after.feasibleMillis()) + " | " + changed + " | " + percent(lost) + " | " + verdict
                + " |\n";
    }

    private static String csvRow(String name, Run before, Run after, int changed, double lost, String verdict) {
        return String.join(
                        ",",
                        name,
                        String.valueOf(before.plan().getPostes().size()),
                        csv(before.ownScore()),
                        csv(after.ownScore()),
                        csv(after.rescored()),
                        String.valueOf(before.feasibleMillis()),
                        String.valueOf(after.feasibleMillis()),
                        String.valueOf(changed),
                        String.format(Locale.ROOT, "%.4f", lost),
                        verdict.replace(',', ';'))
                + "\n";
    }

    private static String levels(HardMediumSoftScore s) {
        return s.hardScore() + "/" + s.mediumScore() + "/" + s.softScore();
    }

    private static String csv(HardMediumSoftScore s) {
        return s.hardScore() + "," + s.mediumScore() + "," + s.softScore();
    }

    private static String seconds(long millis) {
        return millis < 0 ? "jamais" : String.format(Locale.ROOT, "%.1f s", millis / 1000.0);
    }

    private static String percent(double share) {
        return String.format(Locale.ROOT, "%.1f %%", share * 100);
    }

    private static List<String> scenarios() throws IOException {
        List<String> names = new ArrayList<>();
        List<String> only = split(System.getProperty("bench.only", ""));
        for (Path path : ScenariosLivres.all()) {
            String nom = ScenariosLivres.nom(path);
            boolean selected = only.isEmpty()
                    ? (nom.startsWith("gamme-") && !nom.contains("-infaisable-")) || nom.startsWith("festival-")
                    : only.stream().anyMatch(nom::startsWith);
            if (selected) {
                names.add(path.getFileName().toString());
            }
        }
        names.sort(null);
        String local = System.getProperty("bench.scenario");
        if (local != null && !local.isBlank()) {
            names.add(local);
        }
        return names;
    }

    private static List<String> split(String csv) {
        return csv.isBlank() ? List.of() : List.of(csv.split("\\s*,\\s*"));
    }

    private static Map<String, HardMediumSoftScore> parseLevels(String spec) {
        Map<String, HardMediumSoftScore> levels = new HashMap<>();
        for (String entry : split(spec)) {
            String[] parts = entry.split(":");
            if (parts.length < 2 || parts.length > 3) {
                throw new IllegalArgumentException("bench.level expects rule:LEVEL[:weight], got " + entry);
            }
            int weight = parts.length == 3 ? Integer.parseInt(parts[2]) : 1;
            levels.put(
                    parts[0],
                    switch (parts[1].toUpperCase(Locale.ROOT)) {
                        case "HARD" -> HardMediumSoftScore.ofHard(weight);
                        case "MEDIUM" -> HardMediumSoftScore.ofMedium(weight);
                        case "SOFT" -> HardMediumSoftScore.ofSoft(weight);
                        default -> throw new IllegalArgumentException("Unknown level in " + entry);
                    });
        }
        return levels;
    }

    private static void requireKnown(String rule) {
        if (!ConstraintCatalog.PAR_NOM.containsKey(rule)) {
            throw new IllegalArgumentException("Unknown rule: " + rule);
        }
    }
}
