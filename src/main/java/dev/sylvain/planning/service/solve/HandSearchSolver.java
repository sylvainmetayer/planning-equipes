package dev.sylvain.planning.service.solve;

import ai.timefold.solver.core.api.score.HardMediumSoftScore;
import ai.timefold.solver.core.api.solver.SolutionManager;
import ai.timefold.solver.core.api.solver.Solver;
import ai.timefold.solver.core.api.solver.change.ProblemChange;
import ai.timefold.solver.core.api.solver.event.SolverEventListener;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.solver.handsearch.HandSearch;
import dev.sylvain.planning.solver.handsearch.HandSearchException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.jboss.logging.Logger;

/**
 * The direct engine behind the face of a {@link Solver} (ADR 0087):
 * {@link HandSearch} searches, and <b>Timefold referees</b>.
 *
 * <p>The search keeps its own score, the 47 rules written a second time. That
 * score is never believed: once the best plan is laid on the problem, the
 * constraint provider scores it again, and a plan the two do not score alike
 * fails the solve — {@link Failed}, the job in error, the persisted plan
 * untouched. Never a fallback on the other search: an engine that drifted from
 * the rules is a fault to see and fix, and a plan quietly computed otherwise
 * would hide it (the stance of {@link RemoteSolver}, ADR 0086).</p>
 *
 * <p>Like a remote solve, it returns the problem it was given, carrying the
 * plan, where Timefold returns a clone; and it does not change a problem
 * under way.</p>
 */
final class HandSearchSolver implements Solver<PlanningEvenement> {

    private static final Logger LOG = Logger.getLogger(HandSearchSolver.class);

    /** The direct solve failed; the problem is back as it came. The message is what the job shows. */
    static final class Failed extends RuntimeException {
        Failed(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final SolverConfiguration.SearchSettings settings;
    private final long plateauGainMedium;
    private final long seed;
    /**
     * Scores the plan a problem carries and writes that score on it: the
     * constraint provider's {@link SolutionManager#update} in production.
     */
    private final Function<PlanningEvenement, HardMediumSoftScore> referee;

    /** The search itself; replaced by the tests that need a plan the referee refuses. */
    private final Function<PlanningEvenement, HardMediumSoftScore> search;

    private final List<SolverEventListener<PlanningEvenement>> listeners = new CopyOnWriteArrayList<>();
    private volatile boolean stopRequested;
    private volatile boolean solving;

    HandSearchSolver(
            SolverConfiguration.SearchSettings settings,
            long plateauGainMedium,
            SolutionManager<PlanningEvenement, HardMediumSoftScore> referee) {
        // Seed 0, like solverConfig.xml: two solves of one problem search alike.
        this(settings, plateauGainMedium, referee, 0L);
    }

    /** Under another seed than production's: what the bench compares seeds with. */
    HandSearchSolver(
            SolverConfiguration.SearchSettings settings,
            long plateauGainMedium,
            SolutionManager<PlanningEvenement, HardMediumSoftScore> referee,
            long seed) {
        this(settings, plateauGainMedium, referee::update, seed, null);
    }

    /** For the tests: a referee of their own, and a search of their own when {@code search} is not null. */
    HandSearchSolver(
            SolverConfiguration.SearchSettings settings,
            long plateauGainMedium,
            Function<PlanningEvenement, HardMediumSoftScore> referee,
            long seed,
            Function<PlanningEvenement, HardMediumSoftScore> search) {
        this.settings = settings;
        this.plateauGainMedium = plateauGainMedium;
        this.referee = referee;
        this.seed = seed;
        this.search = search == null ? this::search : search;
    }

    @Override
    public PlanningEvenement solve(PlanningEvenement prepared) {
        solving = true;
        List<Animateur> before = new ArrayList<>(prepared.getPostes().size());
        for (PosteAffectation poste : prepared.getPostes()) {
            before.add(poste.getAnimateur());
        }
        HardMediumSoftScore scoreBefore = prepared.getScore();
        try {
            HardMediumSoftScore claimed = search.apply(prepared);
            judge(prepared, claimed);
            return prepared;
        } catch (Failed e) {
            throw restored(prepared, before, scoreBefore, e);
        } catch (HandSearchException e) {
            if (e.isDefect()) {
                // The plan is on the problem: both readings of it, for whoever fixes the engine.
                LOG.errorf(
                        "The direct engine contradicted itself: %s. Read from scratch, rule by rule: %s;"
                                + " the referee scores that plan %s",
                        e.getMessage(), nonZero(breakdownOrNothing(prepared)), scoreOrNothing(prepared));
                throw restored(
                        prepared,
                        before,
                        scoreBefore,
                        new Failed(
                                "Le moteur direct s'est contredit dans son propre score : " + e.getMessage()
                                        + " ; plan refusé, le plan enregistré n'a pas été touché.",
                                e));
            }
            throw restored(
                    prepared,
                    before,
                    scoreBefore,
                    new Failed("Le moteur direct ne peut pas résoudre ce problème : " + e.getMessage() + ".", e));
        } catch (RuntimeException e) {
            // Anything else is a bug of the engine or of what it was handed: the job says so in
            // a sentence, and the problem goes back as it came like after any other failure.
            throw restored(
                    prepared,
                    before,
                    scoreBefore,
                    new Failed(
                            "Le moteur direct a échoué sur une erreur inattendue (" + e
                                    + ") ; le plan enregistré n'a pas été touché.",
                            e));
        } finally {
            solving = false;
        }
    }

    /**
     * Has the constraint provider score the plan the problem carries, and
     * fails the solve when it does not score it as the engine claims.
     */
    private void judge(PlanningEvenement prepared, HardMediumSoftScore claimed) {
        HardMediumSoftScore judged = referee.apply(prepared);
        if (judged.equals(claimed)) {
            return;
        }
        Failed failure = new Failed(
                "Le moteur direct et Timefold ne donnent pas le même score au plan trouvé (" + claimed + " contre "
                        + judged + ") : plan refusé, le plan enregistré n'a pas été touché.",
                null);
        LOG.errorf(
                "The direct engine's score diverged from the referee's: %s Its own score, rule by rule: %s",
                failure.getMessage(), nonZero(breakdownOrNothing(prepared)));
        throw failure;
    }

    private static Map<String, Long> breakdownOrNothing(PlanningEvenement prepared) {
        try {
            return HandSearch.breakdown(prepared);
        } catch (RuntimeException _) {
            return Map.of();
        }
    }

    private String scoreOrNothing(PlanningEvenement prepared) {
        try {
            return String.valueOf(referee.apply(prepared));
        } catch (RuntimeException e) {
            return "(" + e + ")";
        }
    }

    private static Map<String, Long> nonZero(Map<String, Long> breakdown) {
        Map<String, Long> kept = new LinkedHashMap<>(breakdown);
        kept.values().removeIf(points -> points == 0);
        return kept;
    }

    /**
     * Puts every seat back on the holder it came with and the score back as it
     * was, and hands the failure back to be thrown.
     */
    private static Failed restored(
            PlanningEvenement prepared, List<Animateur> before, HardMediumSoftScore scoreBefore, Failed failure) {
        for (int i = 0; i < before.size(); i++) {
            prepared.getPostes().get(i).setAnimateur(before.get(i));
        }
        prepared.setScore(scoreBefore);
        return failure;
    }

    private HardMediumSoftScore search(PlanningEvenement prepared) {
        HandSearch.Limits limits =
                new HandSearch.Limits(settings.seconds(), settings.plateauSeconds(), plateauGainMedium, seed);
        boolean[] feasibilityRefereed = {false};
        return new HandSearch(prepared).solve(limits, () -> stopRequested, (millis, score, lay) -> {
            // « Faisable à N s » is a promise a job makes its operator: the first feasible plan
            // is refereed before it is announced, like the last one before it is returned.
            if (score.isFeasible() && !feasibilityRefereed[0]) {
                lay.run();
                judge(prepared, score);
                feasibilityRefereed[0] = true;
            }
            fire(new BestScore(millis, score));
        });
    }

    private void fire(BestScore event) {
        for (SolverEventListener<PlanningEvenement> listener : listeners) {
            listener.bestSolutionChanged(event);
        }
    }

    @Override
    public boolean terminateEarly() {
        stopRequested = true;
        return true;
    }

    @Override
    public boolean isSolving() {
        return solving;
    }

    @Override
    public boolean isTerminateEarly() {
        return stopRequested;
    }

    @Override
    public void addProblemChange(ProblemChange<PlanningEvenement> problemChange) {
        throw new UnsupportedOperationException("The direct engine does not change a problem under way");
    }

    @Override
    public void addProblemChanges(List<ProblemChange<PlanningEvenement>> problemChangeList) {
        throw new UnsupportedOperationException("The direct engine does not change a problem under way");
    }

    @Override
    public boolean isEveryProblemChangeProcessed() {
        return true;
    }

    @Override
    public void addEventListener(SolverEventListener<PlanningEvenement> eventListener) {
        listeners.add(eventListener);
    }

    @Override
    public void removeEventListener(SolverEventListener<PlanningEvenement> eventListener) {
        listeners.remove(eventListener);
    }
}
