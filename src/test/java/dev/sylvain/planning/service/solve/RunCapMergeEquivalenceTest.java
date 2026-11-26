package dev.sylvain.planning.service.solve;

import static org.assertj.core.api.Assertions.assertThat;

import ai.timefold.solver.core.api.domain.solution.ConstraintWeightOverrides;
import ai.timefold.solver.core.api.score.HardMediumSoftScore;
import dev.sylvain.planning.domain.ConstraintToggle;
import dev.sylvain.planning.domain.ParametresQualite;
import dev.sylvain.planning.domain.PlanningEvenement;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The two forms of the run of days became one rule of adjustable level (ADR
 * 0118). On fixed plans of the shipped scenarios, the merge must change
 * <b>nothing</b>: as a preference, the score of each level and the points of
 * each rule are those of the 3.x dosed form alone; made obligatoire, those of
 * the 3.x hard form alone, under the name of the merged rule.
 *
 * <p>The reference, {@code jours-affilee-avant.txt}, was written on the code
 * <b>before</b> the merge with {@code -Djours.dump=<file>}, the hard form switched
 * on and the dosed form off for the obligatoire lines, and the hard form off for
 * the preference lines; the plans are built from fixed seeds, so the same cases
 * are scored again today. The cap is three days, so that the rule bites on
 * grids of a week. Rewriting the file is a decision, never a convenience: a diff
 * in it is a score that moved.</p>
 */
class RunCapMergeEquivalenceTest {

    private static final String REFERENCE = "/jours-affilee-avant.txt";

    private static final String RULE = "maxJoursConsecutifsTravailles";

    private static final List<String> SCENARIOS = List.of(
            "gamme-09-4j-6stands-14animateurs-typologies-polyvalente-emplacements.yaml",
            "gamme-11-5j-8stands-18animateurs-semaine-a-cheval-nuit.yaml",
            "gamme-12-7j-10stands-24animateurs-semaine-complete-ferie.yaml",
            "gamme-13-7j-12stands-30animateurs-premium-epuisants.yaml",
            "gamme-16-14j-25stands-72animateurs-mineurs-ferie-nocturnes.yaml");

    @Test
    void theMergedRuleScoresWhatTheFormOfItsLevelScoredBefore() throws IOException {
        List<String> now = new ArrayList<>();
        for (boolean obligatoire : List.of(false, true)) {
            now.addAll(scoreEveryCase(obligatoire));
        }
        String dump = System.getProperty("jours.dump");
        if (dump != null) {
            Files.write(Path.of(dump), now, StandardCharsets.UTF_8);
            return;
        }
        assertThat(now).containsExactlyElementsOf(reference());
        // Not an empty promise: the rule bites at both levels.
        assertThat(now).anyMatch(line -> line.contains("| preference |") && line.contains(RULE + "="));
        assertThat(now).anyMatch(line -> line.contains("| obligatoire |") && line.contains(RULE + "="));
    }

    private static List<String> scoreEveryCase(boolean obligatoire) {
        List<String> lines = new ArrayList<>();
        for (String scenario : SCENARIOS) {
            HandSearchFixtures.Loaded loaded = HandSearchFixtures.load(scenario);
            PlanningEvenement problem = loaded.problem();
            problem.setParametresQualite(
                    List.of(threeDays(problem.getParametresQualite().getFirst())));
            List<ConstraintToggle> toggles = new ArrayList<>(problem.getConstraintsDesactivees());
            toggles.removeIf(toggle -> RULE.equals(toggle.getNom()));
            problem.setConstraintsDesactivees(toggles);
            if (obligatoire) {
                // The scenario's own weights kept, the rule's turned into one hard point (ADR 0111).
                ConstraintWeightOverrides<HardMediumSoftScore> weights = problem.getPonderationsContraintes();
                Map<String, HardMediumSoftScore> overrides = new HashMap<>();
                weights.getKnownConstraintIds().forEach(id -> overrides.put(id, weights.getConstraintWeight(id)));
                overrides.put(RULE, HardMediumSoftScore.ofHard(1));
                problem.setPonderationsContraintes(ConstraintWeightOverrides.of(overrides));
            }
            String mode = obligatoire ? "obligatoire" : "preference";
            SplittableRandom random = new SplittableRandom(scenario.hashCode());
            for (int i = 0; i < 3; i++) {
                HandSearchFixtures.randomPlan(problem, random);
                lines.add(line(scenario, mode, "random " + i, loaded));
            }
            for (int i = 0; i < 3; i++) {
                HandSearchFixtures.sparsePlan(problem, random);
                lines.add(line(scenario, mode, "sparse " + i, loaded));
            }
            HandSearchFixtures.overloadedPlan(problem);
            lines.add(line(scenario, mode, "overloaded", loaded));
        }
        return lines;
    }

    private static ParametresQualite threeDays(ParametresQualite q) {
        return new ParametresQualite(
                q.maxEmplacementsDistinctsParJour(),
                q.heureServiceTardif(),
                q.heureServiceMatinal(),
                q.reposSouhaiteApresServiceTardifMinutes(),
                q.typologiesDistinctesMax(),
                3,
                q.vitesseMarcheKmH(),
                q.facteurDetour(),
                q.toleranceTrajetMinutes(),
                q.toleranceArriveeGroupeeMinutes(),
                q.modifieLe());
    }

    private static String line(String scenario, String mode, String plan, HandSearchFixtures.Loaded loaded) {
        HardMediumSoftScore score = loaded.configuration().solutionManager().update(loaded.problem());
        Map<String, Long> points = new TreeMap<>();
        loaded.refereeBreakdown().forEach((rule, lost) -> {
            if (lost != 0) {
                points.put(rule, lost);
            }
        });
        String rules = points.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining(","));
        return scenario + " | " + mode + " | " + plan + " | " + score + " | " + rules;
    }

    private static List<String> reference() throws IOException {
        try (InputStream in = RunCapMergeEquivalenceTest.class.getResourceAsStream(REFERENCE)) {
            assertThat(in).as(REFERENCE).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        }
    }
}
