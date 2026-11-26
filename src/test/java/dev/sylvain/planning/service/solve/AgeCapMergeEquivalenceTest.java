package dev.sylvain.planning.service.solve;

import static org.assertj.core.api.Assertions.assertThat;

import ai.timefold.solver.core.api.score.HardMediumSoftScore;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The daily cap and the continuous-work cap became one rule each, the cap read
 * on the age at the date (ADR 0114). The merge must change <b>nothing</b>: on
 * fixed plans of the shipped scenarios, the score of each level and the
 * points of each rule are those the two rules of each pair gave before, the
 * pair summed under its new name.
 *
 * <p>The reference, {@code plafonds-par-age-avant.txt}, was written by this very
 * test on the code <b>before</b> the merge (the four old names summed into the
 * two new ones below), with {@code -Dplafonds.dump=<file>}; the plans are
 * built from fixed seeds, so the same cases are scored again today. Rewriting
 * it is a decision, never a convenience: a diff in that file is a score that
 * moved.</p>
 */
class AgeCapMergeEquivalenceTest {

    private static final String REFERENCE = "/plafonds-par-age-avant.txt";

    /** What the four rules of 3.x were called, and the rule that replaces each pair. */
    private static final Map<String, String> MERGED = Map.of(
            "dureeQuotidienneMaxMajeur", "dureeQuotidienneMax",
            "dureeQuotidienneMaxMineur", "dureeQuotidienneMax",
            "travailContinuMaxMajeur", "travailContinuMax",
            "travailContinuMaxMineur", "travailContinuMax");

    /** Grids with minors, nights and holidays, from two days to two weeks. */
    private static final List<String> SCENARIOS = List.of(
            "gamme-02-1j-2stands-4animateurs-relais-midi.yaml",
            "gamme-05-2j-4stands-8animateurs-mineurs.yaml",
            "gamme-08-3j-5stands-16animateurs-relais-midi-reduit.yaml",
            "gamme-09-4j-6stands-14animateurs-typologies-polyvalente-emplacements.yaml",
            "gamme-11-5j-8stands-18animateurs-semaine-a-cheval-nuit.yaml",
            "gamme-12-7j-10stands-24animateurs-semaine-complete-ferie.yaml",
            "gamme-13-7j-12stands-30animateurs-premium-epuisants.yaml",
            "gamme-16-14j-25stands-72animateurs-mineurs-ferie-nocturnes.yaml");

    @Test
    void theMergedCapsScoreWhatTheTwoRulesOfEachPairScoredBefore() throws IOException {
        List<String> now = scoreEveryCase();
        String dump = System.getProperty("plafonds.dump");
        if (dump != null) {
            Files.write(Path.of(dump), now, StandardCharsets.UTF_8);
            return;
        }
        assertThat(now).containsExactlyElementsOf(reference());
        // The reference is not an empty promise: the merged rules do bite in it, for both ages.
        String all = String.join("\n", now);
        assertThat(all).contains("dureeQuotidienneMax=").contains("travailContinuMax=");
    }

    private static List<String> scoreEveryCase() {
        List<String> lines = new ArrayList<>();
        for (String scenario : SCENARIOS) {
            HandSearchFixtures.Loaded loaded = HandSearchFixtures.load(scenario);
            SplittableRandom random = new SplittableRandom(scenario.hashCode());
            for (int i = 0; i < 3; i++) {
                HandSearchFixtures.randomPlan(loaded.problem(), random);
                lines.add(line(scenario, "random " + i, loaded));
            }
            for (int i = 0; i < 3; i++) {
                HandSearchFixtures.sparsePlan(loaded.problem(), random);
                lines.add(line(scenario, "sparse " + i, loaded));
            }
            HandSearchFixtures.overloadedPlan(loaded.problem());
            lines.add(line(scenario, "overloaded", loaded));
        }
        return lines;
    }

    private static String line(String scenario, String plan, HandSearchFixtures.Loaded loaded) {
        HardMediumSoftScore score = loaded.configuration().solutionManager().update(loaded.problem());
        Map<String, Long> points = new TreeMap<>();
        loaded.refereeBreakdown().forEach((rule, lost) -> {
            if (lost != 0) {
                points.merge(MERGED.getOrDefault(rule, rule), lost, Long::sum);
            }
        });
        String rules = points.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining(","));
        return scenario + " | " + plan + " | " + score + " | " + rules;
    }

    private static List<String> reference() throws IOException {
        try (InputStream in = AgeCapMergeEquivalenceTest.class.getResourceAsStream(REFERENCE)) {
            assertThat(in).as(REFERENCE).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        }
    }
}
