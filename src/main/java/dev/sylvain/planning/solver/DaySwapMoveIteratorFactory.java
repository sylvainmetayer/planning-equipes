package dev.sylvain.planning.solver;

import ai.timefold.solver.core.impl.heuristic.selector.move.factory.MoveIteratorFactory;
import ai.timefold.solver.core.impl.score.director.ScoreDirector;
import ai.timefold.solver.core.preview.api.domain.metamodel.PlanningSolutionMetaModel;
import ai.timefold.solver.core.preview.api.domain.metamodel.PlanningVariableMetaModel;
import ai.timefold.solver.core.preview.api.move.Move;
import ai.timefold.solver.core.preview.api.move.builtin.Moves;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.random.RandomGenerator;

/**
 * Swaps two animateurs' days: A's movable seats of a date go to B and B's to
 * A, both working that date, in one move.
 *
 * <p>The rules that count stands, locations or game categories per person and
 * day reward a whole day changing hands, and a one-seat move has to make the
 * plan worse before the whole day has moved. Exchanging the two days keeps
 * each person's day count, week and run of days as they were: only what each
 * of them does that day changes.</p>
 *
 * <p>Only pairs where every seat goes to an animateur eligible for it
 * ({@link EligibleAnimateurMoveFilter#isEligible}) are built: the pair is
 * chosen here rather than drawn and then filtered, since two random blocks
 * of the same date are rarely both eligible — which is what made Timefold's
 * own pillar swap unusable (see {@code solverConfig.xml}).</p>
 */
public final class DaySwapMoveIteratorFactory
        implements MoveIteratorFactory<PlanningEvenement, Move<PlanningEvenement>> {

    /** Blocks drawn before giving up for this step; each draw scans every colleague of its date. */
    private static final int BLOCK_TRIES = 20;
    /** How many moves the original-order iterator yields: bounded, since the neighbourhood is combinatorial. */
    private static final int ORIGINAL_MOVES = 1_000;

    private static final PlanningVariableMetaModel<PlanningEvenement, PosteAffectation, Animateur> ANIMATEUR =
            PlanningSolutionMetaModel.of(PlanningEvenement.class, PosteAffectation.class)
                    .genuineEntity(PosteAffectation.class)
                    .basicVariable("animateur", Animateur.class);

    @Override
    public long getSize(ScoreDirector<PlanningEvenement> scoreDirector) {
        PlanningEvenement solution = scoreDirector.getWorkingSolution();
        return (long) solution.getPostes().size() * solution.getAnimateurs().size();
    }

    @Override
    public Iterator<Move<PlanningEvenement>> createOriginalMoveIterator(
            ScoreDirector<PlanningEvenement> scoreDirector) {
        Iterator<Move<PlanningEvenement>> random = createRandomMoveIterator(scoreDirector, new java.util.Random(0));
        return new Iterator<>() {
            private int yielded;

            @Override
            public boolean hasNext() {
                return yielded < ORIGINAL_MOVES && random.hasNext();
            }

            @Override
            public Move<PlanningEvenement> next() {
                yielded++;
                return random.next();
            }
        };
    }

    /**
     * Built once per step, from the plan as it stands. Ends — {@code hasNext()}
     * false — when {@link #BLOCK_TRIES} drawn blocks found nobody to swap with,
     * which the union reads as "draw from the other selectors this step".
     */
    @Override
    public Iterator<Move<PlanningEvenement>> createRandomMoveIterator(
            ScoreDirector<PlanningEvenement> scoreDirector, RandomGenerator random) {
        Index index = new Index(scoreDirector.getWorkingSolution());
        return new Iterator<>() {
            private Move<PlanningEvenement> upcoming;
            private boolean exhausted;

            @Override
            public boolean hasNext() {
                if (upcoming == null && !exhausted) {
                    upcoming = index.nextMove(random);
                    exhausted = upcoming == null;
                }
                return upcoming != null;
            }

            @Override
            public Move<PlanningEvenement> next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                Move<PlanningEvenement> move = upcoming;
                upcoming = null;
                return move;
            }
        };
    }

    /** An animateur's movable seats of one date. */
    record Block(Animateur animateur, LocalDate date, List<PosteAffectation> seats) {}

    /** The plan read once per step: every (animateur, date) block of movable seats, grouped by date. */
    static final class Index {
        private final List<Block> blocks = new ArrayList<>();
        private final Map<LocalDate, List<Block>> blocksOn = new HashMap<>();
        private final ParametresLegaux parametresLegaux;

        Index(PlanningEvenement solution) {
            this.parametresLegaux = solution.parametresLegaux();
            Map<LocalDate, Map<Animateur, List<PosteAffectation>>> byDate = new LinkedHashMap<>();
            for (PosteAffectation poste : solution.getPostes()) {
                Animateur animateur = poste.getAnimateur();
                if (animateur != null && poste.getCreneau() != null && !poste.isVerrouille()) {
                    byDate.computeIfAbsent(poste.getCreneau().getDate(), d -> new LinkedHashMap<>())
                            .computeIfAbsent(animateur, a -> new ArrayList<>())
                            .add(poste);
                }
            }
            byDate.forEach((date, byAnimateur) -> {
                List<Block> day = new ArrayList<>();
                byAnimateur.forEach((animateur, seats) -> day.add(new Block(animateur, date, List.copyOf(seats))));
                blocksOn.put(date, day);
                blocks.addAll(day);
            });
        }

        /**
         * A swap for a block drawn at random — every (animateur, date) block
         * equally likely — with the first colleague of that date, from a random
         * starting point, who can take its seats and give theirs; {@code null}
         * when {@link #BLOCK_TRIES} draws found none.
         */
        Move<PlanningEvenement> nextMove(RandomGenerator random) {
            if (blocks.isEmpty()) {
                return null;
            }
            for (int i = 0; i < BLOCK_TRIES; i++) {
                Block block = blocks.get(random.nextInt(blocks.size()));
                Move<PlanningEvenement> move = swapWithColleague(block, random);
                if (move != null) {
                    return move;
                }
            }
            return null;
        }

        private Move<PlanningEvenement> swapWithColleague(Block block, RandomGenerator random) {
            List<Block> sameDay = blocksOn.get(block.date());
            int size = sameDay.size();
            if (size < 2) {
                return null;
            }
            int start = random.nextInt(size);
            for (int i = 0; i < size; i++) {
                Block other = sameDay.get((start + i) % size);
                if (other.animateur() != block.animateur()
                        && eligible(block.seats(), other.animateur())
                        && eligible(other.seats(), block.animateur())) {
                    List<Move<PlanningEvenement>> changes = new ArrayList<>();
                    give(block.seats(), other.animateur(), changes);
                    give(other.seats(), block.animateur(), changes);
                    return Moves.compose(changes);
                }
            }
            return null;
        }

        private boolean eligible(List<PosteAffectation> seats, Animateur animateur) {
            for (PosteAffectation seat : seats) {
                if (!EligibleAnimateurMoveFilter.isEligible(seat, animateur, parametresLegaux)) {
                    return false;
                }
            }
            return true;
        }

        private static void give(List<PosteAffectation> seats, Animateur to, List<Move<PlanningEvenement>> changes) {
            for (PosteAffectation seat : seats) {
                changes.add(Moves.change(ANIMATEUR, seat, to));
            }
        }
    }
}
