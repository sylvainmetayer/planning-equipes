package dev.sylvain.planning.service.solve;

import static org.assertj.core.api.Assertions.assertThat;

import ai.timefold.solver.core.api.score.HardMediumSoftScore;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.solver.ConstraintCatalog;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The net under the direct engine (ADR 0087): its score, rule by rule, equals
 * the constraint provider's on every plan tried here — not in total, where
 * two errors can cancel out, but <b>constraint by constraint</b>.
 *
 * <p>Each scenario is scored as shipped, then with the branches no versioned
 * scenario carries ({@link HandSearchFixtures#fabricateBranches}), on plans
 * chosen to break things: empty, random with double bookings everywhere,
 * random without them, everything on three people, the plan a short search
 * reaches — whose score, kept move by move, must be the constraint provider's
 * too — and that plan perturbed. A rule the engine scores a point off anywhere fails the case with
 * its name and both values.</p>
 *
 * <p>An equality at zero proves little, so {@link #everyRuleWasScoredAboveZero}
 * closes the class: every constraint of the catalogue must have been compared
 * on a non-zero score at least once, or be named in {@link #NEVER_ABOVE_ZERO}
 * with the reason. A new constraint therefore needs both its term in the
 * engine and a plan here that makes it bite.</p>
 */
class HandSearchDifferentialTest {

    /** Constraints compared on a non-zero score by at least one case of this class. */
    private static final Set<String> ABOVE_ZERO = new ConcurrentSkipListSet<>();

    private static final Set<String> CASES_RUN = new ConcurrentSkipListSet<>();

    /** The cases of the default suite: {@link #everyRuleWasScoredAboveZero} only judges a complete run. */
    private static final int DEFAULT_CASES = 11;

    /**
     * Rules no small scenario makes bite, with what covers them instead. Empty
     * today; an entry needs its reason.
     */
    private static final Map<String, String> NEVER_ABOVE_ZERO = Map.of();

    @ParameterizedTest(name = "{0}, fabricated branches: {1}")
    @CsvSource({
        "gamme-02-1j-2stands-4animateurs-relais-midi.yaml, false",
        "gamme-05-2j-4stands-8animateurs-mineurs.yaml, false",
        "gamme-06-3j-4stands-8animateurs-contraintes-ad-hoc.yaml, false",
        "gamme-06-3j-4stands-8animateurs-contraintes-ad-hoc.yaml, true",
        "gamme-09-4j-6stands-14animateurs-typologies-ninja-emplacements.yaml, false",
        "gamme-09-4j-6stands-14animateurs-typologies-ninja-emplacements.yaml, true",
        "gamme-10-4j-8stands-20animateurs-journees-types-multiples.yaml, true",
        "gamme-11-5j-8stands-18animateurs-semaine-a-cheval-nuit.yaml, true",
        "gamme-12-7j-10stands-24animateurs-semaine-complete-ferie.yaml, true",
        "gamme-13-7j-12stands-30animateurs-premium-epuisants.yaml, false",
        "gamme-16-14j-25stands-72animateurs-mineurs-ferie-nocturnes.yaml, true",
    })
    void scoresEveryRuleLikeTheConstraintProvider(String scenario, boolean fabricated) {
        compare(scenario, fabricated, 1);
        CASES_RUN.add(scenario + fabricated);
    }

    /**
     * A premium stand whose only held seats are remainders of split seats: the
     * constraint provider finds it no crew to compare its faces to and says
     * nothing, where counting « faces beyond a crew of none » would charge it.
     */
    @Test
    void aPremiumStandHeldByRemaindersAloneIsNotChargedForItsFaces() {
        HandSearchFixtures.Loaded loaded =
                HandSearchFixtures.load("gamme-13-7j-12stands-30animateurs-premium-epuisants.yaml");
        List<PosteAffectation> seats = new ArrayList<>(loaded.problem().getPostes());
        PosteAffectation first = seats.stream()
                .filter(poste -> poste.getStand().isPremium())
                .findFirst()
                .orElseThrow();
        List<PosteAffectation> remainders = seats.stream()
                .filter(poste -> poste.getStand().equals(first.getStand()))
                .limit(3)
                .map(poste -> HandSearchFixtures.splitAfter(poste, poste.getDureeEffectiveMinutes() / 2))
                .toList();
        seats.addAll(remainders);
        loaded.problem().setPostes(seats);
        for (int i = 0; i < remainders.size(); i++) {
            remainders.get(i).setAnimateur(loaded.problem().getAnimateurs().get(i));
        }
        agree(loaded, "remainders alone");

        // One origin held again and the stand has a crew of one: three faces for it.
        first.setAnimateur(loaded.problem().getAnimateurs().get(5));
        agree(loaded, "one origin back");
        assertThat(loaded.refereeBreakdown().get("eviterRoulementStandsPremium"))
                .isPositive();
    }

    /** The same on a full-size grid with windows narrowed inside a line, which the small ones do not have. */
    @Test
    @Tag("scenario-lent")
    void scoresEveryRuleLikeTheConstraintProviderOnAFullSizeGrid() {
        compare("festival-hivernal.yaml", false, 5);
        compare("festival-hivernal.yaml", true, 5);
        compare("gamme-25-30j-150stands-320animateurs-edition-complete.yaml", true, 5);
    }

    private static void compare(String scenario, boolean fabricated, long searchSeconds) {
        HandSearchFixtures.Loaded loaded = HandSearchFixtures.load(scenario);
        SplittableRandom random = new SplittableRandom(scenario.hashCode());
        if (fabricated) {
            HandSearchFixtures.search(loaded.problem(), 1, 0);
            HandSearchFixtures.fabricateBranches(loaded.problem(), random);
            HandSearchFixtures.remaindersOnlyPlan(loaded.problem(), random);
            agree(loaded, "remainders only");
        }
        agree(loaded, "empty");
        for (int i = 0; i < 3; i++) {
            HandSearchFixtures.randomPlan(loaded.problem(), random);
            agree(loaded, "random " + i);
        }
        for (int i = 0; i < 3; i++) {
            HandSearchFixtures.sparsePlan(loaded.problem(), random);
            agree(loaded, "without double booking " + i);
        }
        HandSearchFixtures.overloadedPlan(loaded.problem());
        agree(loaded, "overloaded");
        // The one plan reached move by move: the score the search kept through every one of its
        // moves is the constraint provider's, which no comparison of freshly built scores shows.
        HandSearchFixtures.emptyPlan(loaded.problem());
        HardMediumSoftScore kept = HandSearchFixtures.search(loaded.problem(), searchSeconds, 0);
        assertThat(kept)
                .as("the score the search kept move by move, against the constraint provider's")
                .isEqualTo(loaded.configuration().solutionManager().update(loaded.problem()));
        agree(loaded, "searched");
        for (int i = 0; i < 2; i++) {
            HandSearchFixtures.perturb(loaded.problem(), random);
            agree(loaded, "searched then perturbed " + i);
        }
    }

    private static void agree(HandSearchFixtures.Loaded loaded, String plan) {
        assertThat(loaded.disagreements())
                .as("rules scored differently on the %s plan, as « engine / constraint provider »", plan)
                .isEmpty();
        loaded.refereeBreakdown().forEach((constraint, points) -> {
            if (points != 0) {
                ABOVE_ZERO.add(constraint);
            }
        });
    }

    @AfterAll
    static void everyRuleWasScoredAboveZero() {
        if (CASES_RUN.size() < DEFAULT_CASES) {
            return; // a filtered run: nothing to conclude from part of the cases
        }
        Set<String> onlyAtZero = ConstraintCatalog.definitions().stream()
                .map(ConstraintCatalog.ConstraintDefinition::name)
                .filter(name -> !ABOVE_ZERO.contains(name))
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(onlyAtZero)
                .as("constraints only ever compared at zero: give one of the cases a plan that breaks them,"
                        + " or name them in NEVER_ABOVE_ZERO with the reason")
                .isEqualTo(new TreeSet<>(NEVER_ABOVE_ZERO.keySet()));
    }
}
