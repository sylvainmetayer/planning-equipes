package dev.sylvain.planning.solver.constraints;

import ai.timefold.solver.core.api.score.HardMediumSoftScore;
import ai.timefold.solver.core.api.score.stream.Constraint;
import ai.timefold.solver.core.api.score.stream.ConstraintCollectors;
import ai.timefold.solver.core.api.score.stream.ConstraintFactory;
import ai.timefold.solver.core.api.score.stream.Joiners;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.NiveauEffort;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Soft preferences: tie-breakers that shape an otherwise valid plan — spread
 * stands across the event for each animateur, pair beginners with a
 * referent for on-the-job training, and balance who takes the "pénible"
 * slots (physically exhausting or premium stands, issue #79).
 */
public final class PreferenceConstraints {

    /**
     * Same rationale as {@code QualiteConstraints.UNFAIRNESS_SCALE}:
     * {@code loadBalance().unfairness()} is typically well below 1.0, and
     * {@code HardMediumSoftScore} only carries integers — without scaling, a
     * moderate imbalance truncates to exactly 0 and the solver has no gradient
     * to climb to improve fairness.
     */
    private static final BigDecimal UNFAIRNESS_SCALE = BigDecimal.TEN;

    /**
     * How many polyvalent (ninja) animateurs should stay unassigned on any given
     * créneau, so a last-minute absence can be patched by someone who can take
     * over any stand. One is enough to make a plan repairable without freezing a
     * whole reserve of animateurs.
     */
    private static final int POLYVALENTS_LIBRES_MIN = 1;

    public Constraint[] define(ConstraintFactory constraintFactory) {
        return new Constraint[] {
            favoriserMixiteDesNiveaux(constraintFactory),
            equilibrerCreneauxPenibles(constraintFactory),
            preserverBufferPolyvalents(constraintFactory)
        };
    }

    private Constraint favoriserMixiteDesNiveaux(ConstraintFactory constraintFactory) {
        // On a slot that already has a referent, having no beginner is a missed
        // training opportunity (soft, so it never blocks a valid plan).
        return ConstraintToggleSupport.actif(
                        constraintFactory.forEach(PosteAffectation.class), "favoriserMixiteDesNiveaux")
                .filter(poste -> poste.getAnimateur() != null)
                .groupBy(
                        PosteAffectation::getStand,
                        PosteAffectation::getCreneau,
                        ConstraintCollectors.sum(poste -> poste.getAnimateur().isReferentFor(poste.getStand()) ? 1 : 0),
                        ConstraintCollectors.sum(poste -> poste.getAnimateur().isDebutantFor(poste.getStand()) ? 1 : 0))
                // Charged only while a seat of the line is still ahead of now
                // (ADR 0044), an empty one included: a beginner could still
                // be seated there.
                .ifExistsIncludingUnassigned(
                        PosteAffectation.class,
                        Joiners.equal((stand, creneau, referents, debutants) -> stand, PosteAffectation::getStand),
                        Joiners.equal((stand, creneau, referents, debutants) -> creneau, PosteAffectation::getCreneau),
                        Joiners.filtering(
                                (stand, creneau, referents, debutants, poste) -> PastSeats.reproachable(poste)))
                .filter((stand, creneau, referents, debutants) -> referents > 0 && debutants == 0)
                .penalize(HardMediumSoftScore.ONE_SOFT)
                .asConstraint("favoriserMixiteDesNiveaux");
    }

    /**
     * Fairness of "pénible" slots — physically exhausting ({@link NiveauEffort#EPUISANT})
     * or premium stands — across animateurs (issue #79). Same
     * {@code loadBalance} shape as {@code QualiteConstraints.equilibrerCharge},
     * scoped to the subset of postes that are actually pénibles.
     */
    private Constraint equilibrerCreneauxPenibles(ConstraintFactory constraintFactory) {
        return ConstraintToggleSupport.actif(
                        constraintFactory.forEach(PosteAffectation.class), "equilibrerCreneauxPenibles")
                .filter(poste -> poste.getAnimateur() != null && isDemanding(poste.getStand()))
                .groupBy(ConstraintCollectors.loadBalance(PosteAffectation::getAnimateur))
                // The demanding seats already held weigh in the balance; it is
                // charged only while one is still ahead of now (ADR 0044).
                .ifExists(
                        PosteAffectation.class,
                        Joiners.filtering(
                                (balance, poste) -> PastSeats.reproachable(poste) && isDemanding(poste.getStand())))
                .penalize(
                        HardMediumSoftScore.ONE_SOFT,
                        loadBalance -> loadBalance
                                .unfairness()
                                .multiply(UNFAIRNESS_SCALE)
                                .setScale(0, RoundingMode.HALF_UP)
                                .intValue())
                .asConstraint("equilibrerCreneauxPenibles");
    }

    /**
     * Robustness to last-minute absences (issue #83): on every créneau, keep at
     * least {@link #POLYVALENTS_LIBRES_MIN} polyvalent animateur — one holding
     * the referential's "ninja" typologie — free rather than saturating the very
     * profiles able to replace anyone on any stand.
     *
     * <p>Counted against the whole ninja pool of the problem
     * ({@code forEach(Animateur.class)}) rather than against the postes alone,
     * so "free" really means "not assigned anywhere on that créneau". When the
     * referential defines no ninja typologie nobody is polyvalent, the left-hand
     * stream stays empty and the constraint costs nothing.</p>
     *
     * <p>Deliberately in tension with {@code QualiteConstraints.equilibrerCharge}:
     * a polyvalent left idle to stay in reserve is, mechanically, a workload
     * imbalance. Soft against medium, so the balance wins unless everything else
     * is equal — see {@code docs/contraintes.md}.</p>
     */
    private Constraint preserverBufferPolyvalents(ConstraintFactory constraintFactory) {
        return ConstraintToggleSupport.actif(
                        constraintFactory.forEach(PosteAffectation.class), "preserverBufferPolyvalents")
                .filter(poste ->
                        poste.getAnimateur() != null && poste.getAnimateur().isNinja() && poste.getCreneau() != null)
                .groupBy(
                        PosteAffectation::getCreneau,
                        ConstraintCollectors.countDistinct(PosteAffectation::getAnimateur))
                // A timeslot already started keeps whoever it has (ADR 0044):
                // the reserve is only asked of the timeslots still ahead.
                .ifExistsIncludingUnassigned(
                        PosteAffectation.class,
                        Joiners.equal((creneau, occupes) -> creneau, PosteAffectation::getCreneau),
                        Joiners.filtering((creneau, occupes, poste) -> PastSeats.reproachable(poste)))
                .join(constraintFactory
                        .forEach(Animateur.class)
                        .filter(Animateur::isNinja)
                        .groupBy(ConstraintCollectors.count()))
                .filter((creneau, occupes, total) -> total - occupes < POLYVALENTS_LIBRES_MIN)
                .penalize(
                        HardMediumSoftScore.ONE_SOFT,
                        (creneau, occupes, total) -> POLYVALENTS_LIBRES_MIN - (total - occupes))
                .asConstraint("preserverBufferPolyvalents");
    }

    private boolean isDemanding(Stand stand) {
        return stand != null && (stand.getNiveauEffort() == NiveauEffort.EPUISANT || stand.isPremium());
    }
}
