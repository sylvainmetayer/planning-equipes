package dev.sylvain.planning.service.solve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.timefold.solver.core.api.score.HardMediumSoftScore;
import ai.timefold.solver.core.api.solver.Solver;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ConstraintToggle;
import dev.sylvain.planning.domain.NiveauCompetence;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.solver.handsearch.HandSearch;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The direct engine behind the face of a solver (ADR 0087): it solves, it is
 * followed and stopped like any other, and a plan Timefold scores otherwise
 * fails the solve instead of being believed.
 */
class HandSearchSolverTest {

    private static final String SCENARIO = "gamme-12-7j-10stands-24animateurs-semaine-complete-ferie.yaml";

    private static SolverConfiguration.SearchSettings hand(long seconds) {
        return new SolverConfiguration.SearchSettings(seconds, 0, 0, SolverEngine.HAND);
    }

    @Test
    void reachesAFeasiblePlanTheRefereeScoresAlike() {
        HandSearchFixtures.Loaded loaded = HandSearchFixtures.load(SCENARIO);
        HandSearchSolver solver =
                new HandSearchSolver(hand(3), 0, loaded.configuration().solutionManager());
        List<HardMediumSoftScore> bests = new ArrayList<>();
        List<Long> times = new ArrayList<>();
        solver.addEventListener(event -> {
            bests.add((HardMediumSoftScore) event.getNewBestScore());
            times.add(event.getTimeMillisSpent());
        });

        PlanningEvenement solved = solver.solve(loaded.problem());

        assertThat(solved).isSameAs(loaded.problem());
        assertThat(solved.getScore().isFeasible()).isTrue();
        assertThat(solved.getScore())
                .isEqualTo(loaded.configuration().solutionManager().update(solved));
        assertThat(solved.getPostes()).allMatch(poste -> poste.getAnimateur() != null);
        assertThat(loaded.disagreements()).isEmpty();
        assertThat(bests).isNotEmpty().last().isEqualTo(solved.getScore());
        assertThat(times).isSorted();
        assertThat(solver.isSolving()).isFalse();
    }

    @Test
    void stoppedEarlyItHandsBackThePlanItHas() {
        HandSearchFixtures.Loaded loaded = HandSearchFixtures.load(SCENARIO);
        HandSearchSolver solver =
                new HandSearchSolver(hand(120), 0, loaded.configuration().solutionManager());
        // Stopped from its own first announcement, as a job stops the solver it was handed.
        solver.addEventListener(event -> solver.terminateEarly());

        long start = System.nanoTime();
        PlanningEvenement solved = solver.solve(loaded.problem());

        assertThat((System.nanoTime() - start) / 1_000_000_000L).isLessThan(30);
        assertThat(solver.isTerminateEarly()).isTrue();
        assertThat(solved.getScore())
                .isEqualTo(loaded.configuration().solutionManager().update(solved));
    }

    @Test
    void aPlanTheRefereeScoresOtherwiseFailsTheSolveAndLeavesTheProblemAsItCame() {
        HandSearchFixtures.Loaded loaded = HandSearchFixtures.load(SCENARIO);
        // A search that lays a plan and claims a score it does not have: what a rule
        // written wrong in the engine looks like from here.
        HardMediumSoftScore before = loaded.configuration().solutionManager().update(loaded.problem());
        HandSearchSolver solver =
                new HandSearchSolver(null, 0, loaded.configuration().solutionManager()::update, 0, problem -> {
                    HandSearchFixtures.randomPlan(problem, new SplittableRandom(1));
                    return HardMediumSoftScore.of(0, -12, -3);
                });

        assertThatThrownBy(() -> solver.solve(loaded.problem()))
                .isInstanceOf(HandSearchSolver.Failed.class)
                .hasMessageContaining("0hard/-12medium/-3soft")
                .hasMessageContaining("plan refusé");
        assertThat(loaded.problem().getPostes()).allMatch(poste -> poste.getAnimateur() == null);
        // Neither the refused plan nor its score is left behind.
        assertThat(loaded.problem().getScore()).isEqualTo(before);
    }

    /**
     * « Faisable à N s » is said once and believed: the first feasible plan is
     * refereed before it is announced, so a job never calls feasible a plan the
     * constraint provider scores otherwise.
     */
    @Test
    void theFirstFeasiblePlanIsRefereedBeforeItIsAnnounced() {
        HandSearchFixtures.Loaded loaded = HandSearchFixtures.load(SCENARIO);
        // A referee one hard point harsher than the engine, on every plan.
        HandSearchSolver solver = new HandSearchSolver(
                hand(30),
                0,
                problem -> loaded.configuration()
                        .solutionManager()
                        .update(problem)
                        .subtract(HardMediumSoftScore.of(1, 0, 0)),
                0,
                null);
        List<HardMediumSoftScore> announced = new ArrayList<>();
        solver.addEventListener(event -> announced.add((HardMediumSoftScore) event.getNewBestScore()));

        long start = System.nanoTime();
        assertThatThrownBy(() -> solver.solve(loaded.problem()))
                .isInstanceOf(HandSearchSolver.Failed.class)
                .hasMessageContaining("ne donnent pas le même score");

        assertThat(announced).noneMatch(HardMediumSoftScore::isFeasible);
        // Refused at the first feasible plan, not at the end of the budget.
        assertThat((System.nanoTime() - start) / 1_000_000_000L).isLessThan(20);
        assertThat(loaded.problem().getPostes()).allMatch(poste -> poste.getAnimateur() == null);
    }

    @Test
    void anUnexpectedFailureIsWordedAndLeavesTheProblemAsItCame() {
        HandSearchFixtures.Loaded loaded = HandSearchFixtures.load(SCENARIO);
        HandSearchSolver solver =
                new HandSearchSolver(null, 0, loaded.configuration().solutionManager()::update, 0, problem -> {
                    HandSearchFixtures.randomPlan(problem, new SplittableRandom(1));
                    throw new IllegalStateException("boom");
                });

        assertThatThrownBy(() -> solver.solve(loaded.problem()))
                .isInstanceOf(HandSearchSolver.Failed.class)
                .hasMessageContaining("erreur inattendue")
                .hasMessageContaining("boom")
                .hasCauseInstanceOf(IllegalStateException.class);
        assertThat(loaded.problem().getPostes()).allMatch(poste -> poste.getAnimateur() == null);
    }

    @Test
    void aSeatWithoutAnEndOrHeldByAStrangerIsRefusedInWords() {
        HandSearchFixtures.Loaded endless = HandSearchFixtures.load(SCENARIO);
        endless.problem().getPostes().get(0).getCreneau().setHeureFin(null);
        assertThatThrownBy(() -> new HandSearchSolver(
                                hand(1), 0, endless.configuration().solutionManager())
                        .solve(endless.problem()))
                .isInstanceOf(HandSearchSolver.Failed.class)
                .hasMessageContaining("pas d'horaire complet");

        HandSearchFixtures.Loaded stranger = HandSearchFixtures.load(SCENARIO);
        stranger.problem()
                .getPostes()
                .get(0)
                .setAnimateur(new Animateur("STRANGER", "X", "Y", LocalDate.of(1990, 1, 1), false));
        assertThatThrownBy(() -> new HandSearchSolver(
                                hand(1), 0, stranger.configuration().solutionManager())
                        .solve(stranger.problem()))
                .isInstanceOf(HandSearchSolver.Failed.class)
                .hasMessageContaining("absent du problème");
    }

    /**
     * A plan solved, then solved again from itself — a réamorçage with nothing
     * published to hold it — for a million and a half moves. The search used
     * to take every move of equal score, and came back with nine seats in ten
     * swapped between people the rules cannot tell apart; at equal score it
     * now prefers the plan it was handed, and a third of the seats at most
     * move here. The bound on seats changed is the proof; the score cannot
     * come back worse than it left, whatever the search does.
     */
    @Test
    void aPlanSolvedAgainFromItselfKeepsMostSeats() {
        HandSearchFixtures.Loaded loaded =
                HandSearchFixtures.load("gamme-12-7j-10stands-24animateurs-semaine-complete-ferie.yaml");
        List<PosteAffectation> postes = loaded.problem().getPostes();
        HandSearchFixtures.emptyPlan(loaded.problem());
        stoppedAfter(loaded, postes.size() + 1_500, 11);
        Map<String, Animateur> inPlace = new HashMap<>();
        postes.forEach(poste -> inPlace.put(poste.getId(), poste.getAnimateur()));
        HardMediumSoftScore start = loaded.configuration().solutionManager().update(loaded.problem());

        HardMediumSoftScore again = stoppedAfter(loaded, 400, 0);

        assertThat(again).isGreaterThanOrEqualTo(start);
        assertThat(postes.stream()
                        .filter(poste -> poste.getAnimateur() != inPlace.get(poste.getId()))
                        .count())
                .as("seats changed, of %d", postes.size())
                .isLessThanOrEqualTo(postes.size() / 3);
    }

    /**
     * A staffed line with no referent of its stand is charged, and the random
     * moves rarely find the one referent free to take it: the move aimed at
     * such lines leaves far fewer of them in the plan after the same work.
     */
    @Test
    void aimedMoveLeavesFewerLinesWithoutReferent() {
        HandSearchFixtures.Loaded loaded =
                HandSearchFixtures.load("gamme-16-14j-25stands-72animateurs-mineurs-ferie-nocturnes.yaml");
        HandSearchFixtures.emptyPlan(loaded.problem());

        stoppedAfter(loaded, 1_500, 11);

        Map<List<Object>, Boolean> hasReferent = new HashMap<>();
        for (PosteAffectation poste : loaded.problem().getPostes()) {
            if (poste.getAnimateur() != null) {
                hasReferent.merge(
                        List.of(poste.getStand().getId(), poste.getCreneau().getId()),
                        poste.getAnimateur().isReferentFor(poste.getStand()),
                        Boolean::logicalOr);
            }
        }
        long without = hasReferent.values().stream().filter(has -> !has).count();
        // About 270 of 720 without the aimed move, about 170 with it, for the same 1 500 looks.
        assertThat(without)
                .as("lines without a referent, of %d", hasReferent.size())
                .isLessThan(220);
    }

    /** The search from the plan the problem carries, stopped after a fixed number of looks at its stop flag. */
    private static HardMediumSoftScore stoppedAfter(HandSearchFixtures.Loaded loaded, int looks, long seed) {
        int[] looked = {0};
        return new HandSearch(loaded.problem())
                .solve(new HandSearch.Limits(3_600, 0, 0, seed), () -> ++looked[0] > looks, (millis, score, lay) -> {});
    }

    /**
     * A plan handed in — reseeded, incremental — may carry hundreds of seats on
     * somebody the rules no longer allow there, 10 000 hard points each: the
     * hard level has no ceiling a real plan reaches, and the solve goes on.
     */
    @Test
    void aStartingPlanMillionsOfHardPointsDeepIsStillSolved() {
        HandSearchFixtures.Loaded loaded =
                HandSearchFixtures.load("gamme-16-14j-25stands-72animateurs-mineurs-ferie-nocturnes.yaml");
        HandSearchFixtures.overloadedPlan(loaded.problem());
        Set<LocalDate> everyDay = loaded.problem().getPostes().stream()
                .map(poste -> poste.getCreneau().getDate())
                .collect(Collectors.toSet());
        loaded.problem().getAnimateurs().forEach(animateur -> animateur.setJoursIndisponibles(everyDay));
        HardMediumSoftScore start = loaded.configuration().solutionManager().update(loaded.problem());
        assertThat(start.hardScore()).isLessThan(-5_000_000);

        PlanningEvenement solved =
                new HandSearchSolver(hand(1), 0, loaded.configuration().solutionManager()).solve(loaded.problem());

        assertThat(solved.getScore()).isGreaterThanOrEqualTo(start);
        assertThat(loaded.disagreements()).isEmpty();
    }

    /**
     * A team too small for its grid: no plan is feasible, and the search
     * trades empty seats — which it weighs double to decide a move — against
     * other breaches. The plan it keeps is chosen on the real score all the
     * same: the curve never steps back, and what comes out is its last point.
     */
    @ParameterizedTest
    @ValueSource(longs = {0, 1, 2, 3})
    void anUnderstaffedSolveNeverReturnsAPlanWorseThanOneItAnnounced(long seed) {
        HandSearchFixtures.Loaded loaded =
                HandSearchFixtures.load("gamme-12-7j-10stands-24animateurs-semaine-complete-ferie.yaml");
        List<Animateur> team = loaded.problem().getAnimateurs();
        loaded.problem().setAnimateurs(new ArrayList<>(team.subList(0, team.size() * 7 / 10)));
        HandSearchFixtures.emptyPlan(loaded.problem());
        List<HardMediumSoftScore> announced = new ArrayList<>();

        HardMediumSoftScore returned = new HandSearch(loaded.problem())
                .solve(new HandSearch.Limits(3, 0, 0, seed), () -> false, (millis, score, lay) -> announced.add(score));

        assertThat(returned.isFeasible()).isFalse();
        assertThat(announced).isSorted();
        assertThat(returned).isEqualTo(announced.get(announced.size() - 1));
        assertThat(returned).isEqualTo(loaded.configuration().solutionManager().update(loaded.problem()));
    }

    /**
     * A plateau of one second on a team too small for its grid: the window is
     * spent many times over and the solve still runs its four seconds — under
     * both engines the plateau ends a feasible plan only (the configuration
     * ANDs it with feasibility), since reaching one is what the budget is for.
     */
    @Test
    void thePlateauNeverEndsASolveThatHasNoFeasiblePlanYet() {
        HandSearchFixtures.Loaded loaded =
                HandSearchFixtures.load("gamme-12-7j-10stands-24animateurs-semaine-complete-ferie.yaml");
        List<Animateur> team = loaded.problem().getAnimateurs();
        loaded.problem().setAnimateurs(new ArrayList<>(team.subList(0, team.size() * 7 / 10)));
        HandSearchFixtures.emptyPlan(loaded.problem());

        long start = System.nanoTime();
        HardMediumSoftScore returned = new HandSearch(loaded.problem())
                .solve(new HandSearch.Limits(4, 1, 0, 0), () -> false, (millis, score, lay) -> {});

        assertThat(returned.isFeasible()).isFalse();
        assertThat(System.nanoTime() - start).isGreaterThan(3_500_000_000L);
    }

    /**
     * Forty million seat × animateur couples: the table the engine keeps of
     * them would take most of a gigabyte and minutes to fill. Refused, at the
     * size where the other search switches to its sampled construction.
     */
    @Test
    void aProblemTooLargeForTheEnginesTableIsRefusedInWords() {
        HandSearchFixtures.Loaded loaded =
                HandSearchFixtures.load("extreme-09-120j-400stands-1000animateurs-cumul.yaml");

        assertThatThrownBy(() -> new HandSearchSolver(
                                hand(1), 0, loaded.configuration().solutionManager())
                        .solve(loaded.problem()))
                .isInstanceOf(HandSearchSolver.Failed.class)
                .hasMessageContaining("couples siège × animateur");
    }

    @Test
    void aFactCarriedTwiceIsRefusedRatherThanGuessedAt() {
        HandSearchFixtures.Loaded loaded = HandSearchFixtures.load(SCENARIO);
        List<ConstraintToggle> twice = new ArrayList<>(loaded.problem().getConstraintsDesactivees());
        twice.add(new ConstraintToggle("equilibrerCharge", true));
        twice.add(new ConstraintToggle("equilibrerCharge", false));
        loaded.problem().setConstraintsDesactivees(twice);

        assertThatThrownBy(() -> new HandSearchSolver(
                                hand(1), 0, loaded.configuration().solutionManager())
                        .solve(loaded.problem()))
                .isInstanceOf(HandSearchSolver.Failed.class)
                .hasMessageContaining("equilibrerCharge porte deux états");
    }

    @Test
    void aProblemTheEngineDoesNotScoreIsRefusedInWords() {
        HandSearchFixtures.Loaded loaded = HandSearchFixtures.load(SCENARIO);
        Map<String, NiveauCompetence> everything = new HashMap<>();
        for (int i = 0; i < 65; i++) {
            everything.put("TYPOLOGIE-" + i, NiveauCompetence.AUTONOME);
        }
        loaded.problem().getAnimateurs().get(0).setCompetences(everything);
        // Only what a stand offers counts: a fiche may list as many other categories as it likes.
        loaded.problem().getPostes().get(0).getStand().setTypologiesProposees(everything.keySet());
        HandSearchSolver solver =
                new HandSearchSolver(hand(3), 0, loaded.configuration().solutionManager());

        assertThatThrownBy(() -> solver.solve(loaded.problem()))
                .isInstanceOf(HandSearchSolver.Failed.class)
                .hasMessageContaining("Le moteur direct ne peut pas résoudre ce problème")
                .hasMessageContaining("65 typologies de jeu proposées par un stand, pour 64 au plus");
    }

    @Test
    void aSeatHandedInWithItsHolderIsWhereTheSearchStartsAndAPinnedOneDoesNotMove() {
        HandSearchFixtures.Loaded loaded = HandSearchFixtures.load(SCENARIO);
        HandSearchFixtures.search(loaded.problem(), 1, 7);
        PosteAffectation pinned = loaded.problem().getPostes().get(0);
        pinned.setVerrouille(true);
        var holder = pinned.getAnimateur();

        new HandSearchSolver(hand(1), 0, loaded.configuration().solutionManager()).solve(loaded.problem());

        assertThat(pinned.getAnimateur()).isEqualTo(holder);
        assertThat(loaded.problem().getScore().isFeasible()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"extreme-10-sans-animateur.yaml", "extreme-15-sans-creneau.yaml"})
    void aProblemWithNobodyOrNothingToSeatIsScoredAsItStands(String scenario) {
        HandSearchFixtures.Loaded loaded = HandSearchFixtures.load(scenario);

        PlanningEvenement solved =
                new HandSearchSolver(hand(1), 0, loaded.configuration().solutionManager()).solve(loaded.problem());

        assertThat(solved.getScore())
                .isEqualTo(loaded.configuration().solutionManager().update(solved));
        assertThat(loaded.disagreements()).isEmpty();
    }

    /** The path a worker takes: settings alone, no reference data, the engine named by the settings. */
    @Test
    void theEngineNamedByTheSettingsIsTheOneAPreparedProblemIsSolvedWith() {
        HandSearchFixtures.Loaded loaded = HandSearchFixtures.load(SCENARIO);
        SolverConfiguration ofWorker = SolverConfiguration.ofSettings(hand(2));
        AtomicReference<Solver<PlanningEvenement>> handed = new AtomicReference<>();

        SolveRunner.Solved solved = new SolveRunner(ofWorker, null, null)
                .solvePrepared(loaded.problem(), new SolveBudget(2L, 0L, null), handed::set);

        assertThat(handed.get()).isInstanceOf(HandSearchSolver.class);
        assertThat(solved.feasibilityFirst()).isNull();
        assertThat(solved.planning().getScore().isFeasible()).isTrue();
        assertThat(ofWorker.searchSettings(null).engine()).isEqualTo(SolverEngine.HAND);
    }

    @Test
    void theEngineIsReadFromItsNameAndAnUnknownOneIsRefused() {
        assertThat(SolverEngine.of("timefold")).isEqualTo(SolverEngine.TIMEFOLD);
        assertThat(SolverEngine.of(" Hand ")).isEqualTo(SolverEngine.HAND);
        assertThat(SolverEngine.HAND.configValue()).isEqualTo("hand");
        assertThatThrownBy(() -> SolverEngine.of("cp-sat"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("planning.solver.engine");
    }
}
