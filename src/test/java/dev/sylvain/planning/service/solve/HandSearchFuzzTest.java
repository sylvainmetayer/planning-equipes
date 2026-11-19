package dev.sylvain.planning.service.solve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.timefold.solver.core.api.domain.solution.ConstraintWeightOverrides;
import ai.timefold.solver.core.api.score.HardMediumSoftScore;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ConstraintToggle;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.ParametresQualite;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.solver.ConstraintCatalog;
import dev.sylvain.planning.solver.handsearch.HandSearch;
import dev.sylvain.planning.solver.handsearch.HandSearchException;
import dev.sylvain.planning.solver.handsearch.IncrementalProbe;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.TreeMap;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A hunt for plans the direct engine and the constraint provider do not score
 * alike (ADR 0087): where {@link HandSearchDifferentialTest} compares the
 * cases somebody thought of, this one draws problems and plans at random for
 * as long as it is given, and writes down every disagreement instead of
 * stopping at the first.
 *
 * <p>Runs only when {@code -Dfuzz.scenario} names a scenario — a file of
 * {@code src/main/resources/scenarios/}, or any YAML by its absolute path:</p>
 * <ul>
 *   <li>{@code fuzz.seconds} — how long to hunt, 60 by default;</li>
 *   <li>{@code fuzz.seed} — the seed every round derives its own from, 0 by
 *       default;</li>
 *   <li>{@code fuzz.round} — when set, that one round is played and nothing
 *       else: how a line of the report is replayed;</li>
 *   <li>{@code fuzz.out} — the folder the report goes to,
 *       {@code target/fuzz} by default.</li>
 * </ul>
 *
 * <p>A round loads the scenario, moves the problem (see {@link #vary}),
 * compares a series of plans rule by rule, then walks the incremental score
 * through thousands of moves and checks its running total against a score
 * rebuilt from the plan and against the referee. The report carries
 * identifiers and numbers only — never a name, never a date of birth.</p>
 */
@Tag("scenario-lent")
class HandSearchFuzzTest {

    private static final int WALKS = 4;
    private static final int BURSTS_A_WALK = 150;
    private static final int SNAPSHOTS = 4;

    /** Lines written for one rule in one round; the count goes on. */
    private static final int LINES_A_RULE = 3;

    private String scenario;
    private long seed;
    private Path out;
    private long plans;
    private long moves;
    private long refusals;
    private long restartedMoves;
    private final Map<String, Long> divergences = new TreeMap<>();
    private final Map<String, Integer> written = new HashMap<>();

    @Test
    void hunt() throws IOException {
        scenario = System.getProperty("fuzz.scenario");
        assumeTrue(scenario != null && !scenario.isBlank(), "no -Dfuzz.scenario: nothing to hunt on");
        seed = Long.getLong("fuzz.seed", 0L);
        out = Path.of(System.getProperty("fuzz.out", "target/fuzz"));
        Files.createDirectories(out);
        Long only = Long.getLong("fuzz.round");
        long deadline = System.nanoTime() + Long.getLong("fuzz.seconds", 60L) * 1_000_000_000L;

        long rounds = 0;
        for (long round = only == null ? 0 : only; only != null ? rounds == 0 : System.nanoTime() < deadline; round++) {
            round(round);
            rounds++;
        }

        String summary = "FUZZ | " + scenario + " | seed " + seed + " | rounds " + rounds + " | plans compared " + plans
                + " | moves " + moves + " | problems refused " + refusals + " | divergences " + divergences;
        System.out.println(summary);
        append("summary.md", summary);
        assertThat(divergences).as(summary).isEmpty();
    }

    private void round(long round) {
        written.clear();
        SplittableRandom random = new SplittableRandom(seed * 1_000_003L + round);
        List<String> variations = new ArrayList<>();
        String plan = "loading";
        try {
            HandSearchFixtures.Loaded loaded = HandSearchFixtures.load(scenario);
            PlanningEvenement problem = loaded.problem();
            vary(problem, random, variations);

            plan = "empty";
            HandSearchFixtures.emptyPlan(problem);
            compare(loaded, round, variations, plan);
            plan = "random";
            HandSearchFixtures.randomPlan(problem, random);
            compare(loaded, round, variations, plan);
            plan = "without double booking";
            HandSearchFixtures.sparsePlan(problem, random);
            compare(loaded, round, variations, plan);
            plan = "overloaded";
            HandSearchFixtures.overloadedPlan(problem);
            compare(loaded, round, variations, plan);

            plan = "search";
            HandSearchFixtures.emptyPlan(problem);
            int[] seen = {0};
            long searchSeed = random.nextLong();
            try {
                HardMediumSoftScore kept = new HandSearch(problem)
                        .solve(new HandSearch.Limits(2, 0, 0, searchSeed), () -> false, (millis, score, lay) -> {
                            // Every eighth plan the search announces, a few of them: greedy first, then mid-search.
                            if (seen[0]++ % 8 == 0 && seen[0] / 8 < SNAPSHOTS) {
                                lay.run();
                                compare(loaded, round, variations, "search snapshot");
                            }
                        });
                compare(loaded, round, variations, "search result");
                HardMediumSoftScore referee =
                        loaded.configuration().solutionManager().update(problem);
                if (!kept.equals(referee)) {
                    diverged(round, variations, "search result", "the score the search kept", kept + " / " + referee);
                }
            } catch (HandSearchException drifted) {
                if (!drifted.isDefect()) {
                    throw drifted;
                }
                // The search's own check at its end: written down, and the round goes on to the walk.
                diverged(round, variations, plan, "the engine contradicted itself", drifted.getMessage());
            }

            plan = "perturbed";
            HandSearchFixtures.perturb(problem, random);
            compare(loaded, round, variations, plan);
            plan = "partly emptied";
            for (PosteAffectation poste : problem.getPostes()) {
                if (HandSearchFixtures.movable(poste) && random.nextInt(10) < 3) {
                    poste.setAnimateur(null);
                }
            }
            compare(loaded, round, variations, plan);

            plan = "walk";
            IncrementalProbe probe = new IncrementalProbe(problem);
            for (int walk = 0; walk < WALKS; walk++) {
                // Short bursts: a cost left stale by one move is put right by the next move that
                // touches the same animateur, so a check every few thousand moves would see nothing.
                for (int burst = 0; burst < BURSTS_A_WALK; burst++) {
                    probe.walk(random, random.nextInt(1, 60));
                    String drift = probe.drift();
                    if (drift != null) {
                        diverged(round, variations, "walk, " + probe.moves() + " moves", "the running total", drift);
                        // One drift taints every total after it: start again from the plan as it is.
                        probe = restarted(probe, problem);
                    }
                }
                String at = "walk, " + probe.moves() + " moves";
                probe.lay();
                compare(loaded, round, variations, at);
                HardMediumSoftScore walked =
                        loaded.configuration().solutionManager().update(problem);
                if (!probe.held().equals(walked)) {
                    diverged(round, variations, at, "the score the walk holds", probe.held() + " / " + walked);
                }
            }
            moves += probe.moves() + restartedMoves;
            restartedMoves = 0;
        } catch (HandSearchException refused) {
            if (refused.isDefect()) {
                diverged(round, variations, plan, "the engine contradicted itself", refused.getMessage());
            } else {
                // A shape of problem the engine declines in words: not a disagreement.
                refusals++;
                append("refusals.md", line(round, variations, plan, "refused", refused.getMessage()));
            }
        } catch (RuntimeException failed) {
            // The class and where it was thrown, never the message: it may quote the problem.
            StackTraceElement[] trace = failed.getStackTrace();
            diverged(
                    round,
                    variations,
                    plan,
                    "exception",
                    failed.getClass().getSimpleName() + (trace.length == 0 ? "" : " at " + trace[0]));
        }
    }

    /** A fresh walk from the plan the drifted one is on; its moves are kept for the summary. */
    private IncrementalProbe restarted(IncrementalProbe drifted, PlanningEvenement problem) {
        drifted.lay();
        restartedMoves += drifted.moves();
        return new IncrementalProbe(problem);
    }

    /* ----------------------------- variations ----------------------------- */

    /**
     * Moves the problem, each family on the toss of a coin: the fabricated
     * branches of the differential test (locks, pinned and past seats, a
     * published plan, the five kinds of ad hoc exception, game-category
     * ceilings, ages that change during the event, unknown birth dates, split
     * seats), then rules switched off, weights, legal and quality parameters,
     * more unknown birth dates, more splits and more pinned seats.
     */
    private static void vary(PlanningEvenement problem, SplittableRandom random, List<String> variations) {
        if (random.nextInt(10) < 7) {
            HandSearchFixtures.sparsePlan(problem, random);
            HandSearchFixtures.fabricateBranches(problem, random);
            variations.add("branches");
        }
        List<String> rules = ConstraintCatalog.definitions().stream()
                .map(ConstraintCatalog.ConstraintDefinition::name)
                .toList();
        if (random.nextBoolean()) {
            List<ConstraintToggle> toggles = new ArrayList<>(problem.getConstraintsDesactivees());
            for (int i = random.nextInt(1, 5); i > 0; i--) {
                String rule = rules.get(random.nextInt(rules.size()));
                toggles.removeIf(toggle -> toggle.getNom().equals(rule));
                toggles.add(new ConstraintToggle(rule, false));
                variations.add("off " + rule);
            }
            problem.setConstraintsDesactivees(toggles);
        }
        if (random.nextBoolean()) {
            Map<String, HardMediumSoftScore> weights = new HashMap<>();
            ConstraintWeightOverrides<HardMediumSoftScore> current = problem.getPonderationsContraintes();
            current.getKnownConstraintIds().forEach(rule -> weights.put(rule, current.getConstraintWeight(rule)));
            for (int i = random.nextInt(1, 5); i > 0; i--) {
                ConstraintCatalog.ConstraintDefinition rule =
                        ConstraintCatalog.definitions().get(random.nextInt(rules.size()));
                int weight = new int[] {0, 2, 3, 7}[random.nextInt(4)];
                weights.put(
                        rule.name(),
                        switch (rule.niveau()) {
                            case HARD -> HardMediumSoftScore.ofHard(weight);
                            case MEDIUM -> HardMediumSoftScore.ofMedium(weight);
                            case SOFT -> HardMediumSoftScore.ofSoft(weight);
                        });
                variations.add("weight " + rule.name() + "=" + weight);
            }
            problem.setPonderationsContraintes(ConstraintWeightOverrides.of(weights));
        }
        if (random.nextBoolean()) {
            ParametresLegaux legal = problem.parametresLegaux();
            int pause = new int[] {20, 30, 45, 60, 90, 120}[random.nextInt(6)];
            int meal = new int[] {30, 45, 60, 90}[random.nextInt(4)];
            int noon = random.nextInt(11, 13);
            int evening = random.nextInt(18, 20);
            int rest = new int[] {600, 660, 720, 780}[random.nextInt(4)];
            int week = new int[] {1_800, 2_400, 2_880}[random.nextInt(3)];
            int shift = new int[] {360, 480, 600}[random.nextInt(3)];
            legal.setDureePauseMinutes(pause);
            legal.setCoupureRepasMinutes(meal);
            legal.setCoupureRepasMidiDebut(LocalTime.of(noon, 30));
            legal.setCoupureRepasMidiFin(LocalTime.of(noon + 2, 0));
            legal.setCoupureRepasSoirDebut(LocalTime.of(evening, 0));
            legal.setCoupureRepasSoirFin(LocalTime.of(evening + 2, 30));
            legal.setReposQuotidienMinimalMinutes(rest);
            legal.setDureeHebdomadaireMaxMinutes(week);
            legal.setDureeHebdomadaireMaxMineurMinutes(week - 600);
            legal.setDureeVacationMaxMinutes(shift);
            legal.setHeureDebutSoiree(LocalTime.of(evening, 30));
            variations.add("legal pause=" + pause + " meal=" + meal + " noon=" + noon + " evening=" + evening + " rest="
                    + rest + " week=" + week + " shift=" + shift);
        }
        if (random.nextBoolean()) {
            ParametresQualite quality = new ParametresQualite(
                    random.nextInt(1, 5),
                    LocalTime.of(random.nextInt(20, 24), 0),
                    LocalTime.of(random.nextInt(8, 12), 0),
                    new int[] {600, 720, 840}[random.nextInt(3)],
                    random.nextInt(1, 5),
                    random.nextInt(2, 7),
                    random.nextBoolean() ? 3.0 : 4.5,
                    random.nextBoolean() ? 1.0 : 1.3,
                    new int[] {0, 5, 15}[random.nextInt(3)],
                    new int[] {0, 15, 30}[random.nextInt(3)]);
            problem.setParametresQualite(List.of(quality));
            variations.add("quality " + quality.maxEmplacementsDistinctsParJour() + "/"
                    + quality.heureServiceTardif() + "/" + quality.heureServiceMatinal() + "/"
                    + quality.reposSouhaiteApresServiceTardifMinutes() + "/" + quality.typologiesDistinctesMax() + "/"
                    + quality.joursConsecutifsMax() + "/" + quality.vitesseMarcheKmH() + "/" + quality.facteurDetour()
                    + "/" + quality.toleranceTrajetMinutes() + "/" + quality.toleranceArriveeGroupeeMinutes());
        }
        List<Animateur> animateurs = problem.getAnimateurs();
        if (!animateurs.isEmpty() && random.nextInt(10) < 3) {
            int unknown = random.nextInt(1, 4);
            for (int i = 0; i < unknown; i++) {
                animateurs.get(random.nextInt(animateurs.size())).setDateNaissance(null);
            }
            variations.add(unknown + " birth dates unknown");
        }
        if (!animateurs.isEmpty() && random.nextInt(10) < 3) {
            // Somebody comes of age on a day of the event: a minor before it, an adult from it on.
            List<LocalDate> days = problem.getPostes().stream()
                    .map(poste -> poste.getCreneau().getDate())
                    .distinct()
                    .sorted()
                    .toList();
            int day = random.nextInt(days.size());
            animateurs
                    .get(random.nextInt(animateurs.size()))
                    .setDateNaissance(days.get(day).minusYears(18));
            variations.add("an eighteenth birthday on day " + day);
        }
        if (random.nextInt(10) < 3) {
            List<PosteAffectation> all = new ArrayList<>(problem.getPostes());
            int split = 0;
            for (PosteAffectation poste : problem.getPostes()) {
                if (split < 5
                        && random.nextInt(20) == 0
                        && HandSearchFixtures.movable(poste)
                        && poste.getSuiteDe() == null
                        && poste.getHeureFinEffective() == null
                        && poste.getDureeEffectiveMinutes() >= 120) {
                    all.add(HandSearchFixtures.splitAfter(poste, poste.getDureeEffectiveMinutes() / 2));
                    split++;
                }
            }
            problem.setPostes(all);
            variations.add(split + " more seats split");
        }
        if (random.nextInt(10) < 3) {
            HandSearchFixtures.sparsePlan(problem, random);
            int pinned = 0;
            for (PosteAffectation poste : problem.getPostes()) {
                if (HandSearchFixtures.movable(poste) && poste.getAnimateur() != null && random.nextInt(20) == 0) {
                    poste.setVerrouille(true);
                    pinned++;
                }
            }
            variations.add(pinned + " more seats pinned");
        }
    }

    /* ------------------------------- report ------------------------------- */

    private void compare(HandSearchFixtures.Loaded loaded, long round, List<String> variations, String plan) {
        plans++;
        loaded.disagreements().forEach((rule, values) -> diverged(round, variations, plan, rule, values));
    }

    private void diverged(long round, List<String> variations, String plan, String rule, String values) {
        divergences.merge(rule, 1L, Long::sum);
        if (written.merge(rule, 1, Integer::sum) <= LINES_A_RULE) {
            String line = line(round, variations, plan, rule, values);
            System.out.println("FUZZ DIVERGENCE " + line);
            append("divergences.md", line);
        }
    }

    /** Everything a round needs to be played again, and what it found: own / referee. */
    private String line(long round, List<String> variations, String plan, String rule, String values) {
        return "| " + scenario + " | seed " + seed + " | round " + round + " | " + String.join(", ", variations) + " | "
                + plan + " | " + rule + " | " + values + " |";
    }

    private void append(String file, String line) {
        try {
            Files.writeString(
                    out.resolve(file),
                    line + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
