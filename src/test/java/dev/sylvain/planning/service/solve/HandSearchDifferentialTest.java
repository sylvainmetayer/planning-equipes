package dev.sylvain.planning.service.solve;

import static org.assertj.core.api.Assertions.assertThat;

import ai.timefold.solver.core.api.score.HardMediumSoftScore;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.NiveauCompetence;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.solver.ConstraintCatalog;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
import org.junit.jupiter.params.provider.ValueSource;

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

    /**
     * The choosable rules compared on a non-zero score at each level they can
     * be given (ADR 0106): « OBLIGATOIRE » and « PREFERENCE » name the level
     * every choosable rule of the case was moved to.
     */
    private static final Map<String, Set<String>> ABOVE_ZERO_AT_LEVEL = Map.of(
            "OBLIGATOIRE", new ConcurrentSkipListSet<>(),
            "PREFERENCE", new ConcurrentSkipListSet<>());

    private static final Set<String> LEVEL_CASES_RUN = new ConcurrentSkipListSet<>();

    /** The cases of {@link #scoresEveryChoosableRuleAtEachLevelLikeTheConstraintProvider}. */
    private static final int LEVEL_CASES = 18;

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
        "gamme-09-4j-6stands-14animateurs-typologies-polyvalente-emplacements.yaml, false",
        "gamme-09-4j-6stands-14animateurs-typologies-polyvalente-emplacements.yaml, true",
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
     * The level an edition chooses for an organisation rule (ADR 0106) moves
     * where the rule scores, not what it counts: the two engines must agree
     * <b>at each level</b>, with every choosable rule moved together to the hard
     * level, and to the medium level — which is also the catalogue's own for
     * all but the meal break and the adult beside a minor, so the shipped
     * level of those two is covered by the cases above.
     */
    @ParameterizedTest(name = "{0}, fabricated branches: {1}, every choosable rule {2}")
    @CsvSource({
        "gamme-02-1j-2stands-4animateurs-relais-midi.yaml, false, OBLIGATOIRE",
        "gamme-02-1j-2stands-4animateurs-relais-midi.yaml, false, PREFERENCE",
        "gamme-05-2j-4stands-8animateurs-mineurs.yaml, false, OBLIGATOIRE",
        "gamme-05-2j-4stands-8animateurs-mineurs.yaml, false, PREFERENCE",
        "gamme-06-3j-4stands-8animateurs-contraintes-ad-hoc.yaml, true, OBLIGATOIRE",
        "gamme-06-3j-4stands-8animateurs-contraintes-ad-hoc.yaml, true, PREFERENCE",
        "gamme-09-4j-6stands-14animateurs-typologies-polyvalente-emplacements.yaml, true, OBLIGATOIRE",
        "gamme-09-4j-6stands-14animateurs-typologies-polyvalente-emplacements.yaml, true, PREFERENCE",
        "gamme-10-4j-8stands-20animateurs-journees-types-multiples.yaml, true, OBLIGATOIRE",
        "gamme-10-4j-8stands-20animateurs-journees-types-multiples.yaml, true, PREFERENCE",
        "gamme-11-5j-8stands-18animateurs-semaine-a-cheval-nuit.yaml, true, OBLIGATOIRE",
        "gamme-11-5j-8stands-18animateurs-semaine-a-cheval-nuit.yaml, true, PREFERENCE",
        "gamme-12-7j-10stands-24animateurs-semaine-complete-ferie.yaml, true, OBLIGATOIRE",
        "gamme-12-7j-10stands-24animateurs-semaine-complete-ferie.yaml, true, PREFERENCE",
        "gamme-13-7j-12stands-30animateurs-premium-epuisants.yaml, false, OBLIGATOIRE",
        "gamme-13-7j-12stands-30animateurs-premium-epuisants.yaml, false, PREFERENCE",
        "gamme-16-14j-25stands-72animateurs-mineurs-ferie-nocturnes.yaml, true, OBLIGATOIRE",
        "gamme-16-14j-25stands-72animateurs-mineurs-ferie-nocturnes.yaml, true, PREFERENCE",
    })
    void scoresEveryChoosableRuleAtEachLevelLikeTheConstraintProvider(
            String scenario, boolean fabricated, String level) {
        compare(scenario, fabricated, 1, level);
        LEVEL_CASES_RUN.add(scenario + fabricated + level);
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
        agree(null, loaded, "remainders alone");

        // One origin held again and the stand has a crew of one: three faces for it.
        first.setAnimateur(loaded.problem().getAnimateurs().get(5));
        agree(null, loaded, "one origin back");
        assertThat(loaded.refereeBreakdown().get("eviterRoulementStandsPremium"))
                .isPositive();
    }

    /**
     * A break of an hour and a half, which the setting gives minors as well:
     * the relay of a break is looked for among the seats that overlap the one
     * held, however long the break. The second grid has minors and nights that
     * end after midnight, where the relay may be a seat dated the day before.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "gamme-08-3j-5stands-16animateurs-relais-midi-reduit.yaml",
                "gamme-16-14j-25stands-72animateurs-mineurs-ferie-nocturnes.yaml"
            })
    void aBreakLongerThanAnHourIsRelayedAlike(String scenario) {
        HandSearchFixtures.Loaded loaded = HandSearchFixtures.load(scenario);
        loaded.problem().parametresLegaux().setDureePauseMinutes(90);
        SplittableRandom random = new SplittableRandom(8);
        // A solved plan first: dense and legal, it is where a break is owed and somebody does cover it.
        HandSearchFixtures.search(loaded.problem(), 2, 0);
        agree(null, loaded, "break of 90 minutes, solved");
        long relayed = 0;
        long minors = 0;
        for (int i = 0; i < 6; i++) {
            if (i < 3) {
                HandSearchFixtures.randomPlan(loaded.problem(), random);
            } else {
                HandSearchFixtures.sparsePlan(loaded.problem(), random);
            }
            agree(null, loaded, "break of 90 minutes " + i);
            relayed += loaded.refereeBreakdown().getOrDefault("travailContinuMaxMajeur", 0L);
            minors += loaded.refereeBreakdown().getOrDefault("travailContinuMaxMineur", 0L);
        }
        HandSearchFixtures.overloadedPlan(loaded.problem());
        agree(null, loaded, "break of 90 minutes, overloaded");
        relayed += loaded.refereeBreakdown().getOrDefault("travailContinuMaxMajeur", 0L);
        assertThat(relayed).as("unrelayed breaks compared").isPositive();
        if (scenario.contains("mineurs")) {
            assertThat(minors).as("unrelayed breaks of minors compared").isPositive();
        }
    }

    /**
     * A stretch from 18:30 to 1:00 owes its break at half past midnight, on a
     * seat dated the day before; the only colleague there holds a seat dated
     * the next day. That seat relays the break, and without its holder the
     * break is unrelayed — under both scores.
     */
    @Test
    void aBreakDueAfterMidnightIsRelayedByASeatDatedTheNextDay() {
        HandSearchFixtures.Loaded loaded =
                HandSearchFixtures.load("gamme-11-5j-8stands-18animateurs-semaine-a-cheval-nuit.yaml");
        PlanningEvenement problem = loaded.problem();
        PosteAffectation night = problem.getPostes().stream()
                .filter(poste -> LocalTime.of(21, 0).equals(poste.getCreneau().getHeureDebut()))
                .findFirst()
                .orElseThrow();
        Creneau evening = night.getCreneau();
        LocalDate day = evening.getDate();
        PosteAffectation early = new PosteAffectation(
                "EARLY",
                night.getStand(),
                new Creneau(9_001L, evening.getJour(), day, LocalTime.of(18, 30), LocalTime.of(21, 0)));
        PosteAffectation afterMidnight = new PosteAffectation(
                "AFTER-MIDNIGHT",
                night.getStand(),
                new Creneau(9_002L, evening.getJour() + 1, day.plusDays(1), LocalTime.MIDNIGHT, LocalTime.of(1, 0)));
        List<PosteAffectation> postes = new ArrayList<>(problem.getPostes());
        postes.add(early);
        postes.add(afterMidnight);
        problem.setPostes(postes);
        HandSearchFixtures.emptyPlan(problem);
        List<Animateur> adults = problem.getAnimateurs().stream()
                .filter(animateur -> animateur.isMajeurOn(day))
                .toList();
        early.setAnimateur(adults.get(0));
        night.setAnimateur(adults.get(0));

        afterMidnight.setAnimateur(adults.get(1));
        agree(null, loaded, "break after midnight, relayed");
        assertThat(loaded.refereeBreakdown().getOrDefault("travailContinuMaxMajeur", 0L))
                .isZero();

        afterMidnight.setAnimateur(null);
        agree(null, loaded, "break after midnight, nobody to relay it");
        assertThat(loaded.refereeBreakdown().getOrDefault("travailContinuMaxMajeur", 0L))
                .isPositive();
    }

    /**
     * More game categories than a {@code long} has bits: each animateur's are
     * numbered for them alone, so the edition may have as many as it likes.
     */
    @Test
    void anEditionWithMoreThanSixtyFourGameCategoriesIsScoredAlike() {
        HandSearchFixtures.Loaded loaded =
                HandSearchFixtures.load("gamme-09-4j-6stands-14animateurs-typologies-polyvalente-emplacements.yaml");
        List<Stand> stands = loaded.problem().getPostes().stream()
                .map(PosteAffectation::getStand)
                .distinct()
                .toList();
        for (int i = 0; i < 90; i++) {
            Stand stand = stands.get(i % stands.size());
            Set<String> offered = new HashSet<>(stand.getTypologiesProposees());
            offered.add("EXTRA-" + i);
            stand.setTypologiesProposees(offered);
        }
        List<Animateur> animateurs = loaded.problem().getAnimateurs();
        for (int a = 0; a < animateurs.size(); a++) {
            Map<String, NiveauCompetence> competences =
                    new HashMap<>(animateurs.get(a).getCompetences());
            for (int i = 0; i < 20; i++) {
                competences.put("EXTRA-" + (a * 7 + i * 4) % 90, NiveauCompetence.AUTONOME);
            }
            animateurs.get(a).setCompetences(competences);
        }
        // One fiche at the very limit — sixty-four categories a stand offers — and ten more nobody offers.
        Map<String, NiveauCompetence> atTheLimit = new HashMap<>();
        for (int i = 0; i < 64; i++) {
            atTheLimit.put("EXTRA-" + i, NiveauCompetence.AUTONOME);
        }
        for (int i = 0; i < 10; i++) {
            atTheLimit.put("NOWHERE-" + i, NiveauCompetence.AUTONOME);
        }
        animateurs.get(0).setCompetences(atTheLimit);
        SplittableRandom random = new SplittableRandom(9);
        long spread = 0;
        for (int i = 0; i < 6; i++) {
            if (i < 3) {
                HandSearchFixtures.randomPlan(loaded.problem(), random);
            } else {
                HandSearchFixtures.sparsePlan(loaded.problem(), random);
            }
            agree(null, loaded, "ninety more game categories " + i);
            spread += loaded.refereeBreakdown().getOrDefault("limiterTypologiesDistinctesParAnimateur", 0L);
        }
        assertThat(spread)
                .as("animateurs spread over too many categories, compared")
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
        compare(scenario, fabricated, searchSeconds, null);
    }

    /** {@code level} names the level every choosable rule is moved to, or is {@code null} for the catalogue's. */
    private static void compare(String scenario, boolean fabricated, long searchSeconds, String level) {
        HandSearchFixtures.Loaded loaded = HandSearchFixtures.load(scenario);
        if (level != null) {
            Map<String, Boolean> chosen = new HashMap<>();
            ConstraintCatalog.definitions().stream()
                    .filter(ConstraintCatalog.ConstraintDefinition::niveauReglable)
                    .forEach(definition -> chosen.put(definition.name(), "OBLIGATOIRE".equals(level)));
            loaded.problem().setPonderationsContraintes(loaded.configuration().constraintWeightOverrides(null, chosen));
        }
        SplittableRandom random = new SplittableRandom(scenario.hashCode());
        if (fabricated) {
            HandSearchFixtures.search(loaded.problem(), 1, 0);
            HandSearchFixtures.fabricateBranches(loaded.problem(), random);
            HandSearchFixtures.remaindersOnlyPlan(loaded.problem(), random);
            agree(level, loaded, "remainders only");
        }
        agree(level, loaded, "empty");
        for (int i = 0; i < 3; i++) {
            HandSearchFixtures.randomPlan(loaded.problem(), random);
            agree(level, loaded, "random " + i);
        }
        for (int i = 0; i < 3; i++) {
            HandSearchFixtures.sparsePlan(loaded.problem(), random);
            agree(level, loaded, "without double booking " + i);
        }
        HandSearchFixtures.overloadedPlan(loaded.problem());
        agree(level, loaded, "overloaded");
        // The one plan reached move by move: the score the search kept through every one of its
        // moves is the constraint provider's, which no comparison of freshly built scores shows.
        HandSearchFixtures.emptyPlan(loaded.problem());
        HardMediumSoftScore kept = HandSearchFixtures.search(loaded.problem(), searchSeconds, 0);
        assertThat(kept)
                .as("the score the search kept move by move, against the constraint provider's")
                .isEqualTo(loaded.configuration().solutionManager().update(loaded.problem()));
        agree(level, loaded, "searched");
        for (int i = 0; i < 2; i++) {
            HandSearchFixtures.perturb(loaded.problem(), random);
            agree(level, loaded, "searched then perturbed " + i);
        }
    }

    private static void agree(String level, HandSearchFixtures.Loaded loaded, String plan) {
        assertThat(loaded.disagreements())
                .as("rules scored differently on the %s plan, as « engine / constraint provider »", plan)
                .isEmpty();
        loaded.refereeBreakdown().forEach((constraint, points) -> {
            if (points != 0) {
                (level == null ? ABOVE_ZERO : ABOVE_ZERO_AT_LEVEL.get(level)).add(constraint);
            }
        });
    }

    @AfterAll
    static void everyRuleWasScoredAboveZero() {
        if (CASES_RUN.size() >= DEFAULT_CASES) {
            Set<String> onlyAtZero = ConstraintCatalog.definitions().stream()
                    .map(ConstraintCatalog.ConstraintDefinition::name)
                    .filter(name -> !ABOVE_ZERO.contains(name))
                    .collect(Collectors.toCollection(TreeSet::new));
            assertThat(onlyAtZero)
                    .as("constraints only ever compared at zero: give one of the cases a plan that breaks them,"
                            + " or name them in NEVER_ABOVE_ZERO with the reason")
                    .isEqualTo(new TreeSet<>(NEVER_ABOVE_ZERO.keySet()));
        }
        if (LEVEL_CASES_RUN.size() >= LEVEL_CASES) {
            // « Above zero » is judged per level, or a level would stay checked by nobody (ADR 0106).
            ABOVE_ZERO_AT_LEVEL.forEach((level, seen) -> {
                Set<String> onlyAtZero = ConstraintCatalog.definitions().stream()
                        .filter(ConstraintCatalog.ConstraintDefinition::niveauReglable)
                        .map(ConstraintCatalog.ConstraintDefinition::name)
                        .filter(name -> !seen.contains(name))
                        .collect(Collectors.toCollection(TreeSet::new));
                assertThat(onlyAtZero)
                        .as("choosable rules only ever compared at zero at the level " + level)
                        .isEmpty();
            });
        }
    }
}
