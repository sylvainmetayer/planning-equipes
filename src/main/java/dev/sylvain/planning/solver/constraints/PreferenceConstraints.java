package dev.sylvain.planning.solver.constraints;

import java.math.BigDecimal;
import java.math.RoundingMode;

import ai.timefold.solver.core.api.score.buildin.hardmediumsoft.HardMediumSoftScore;
import ai.timefold.solver.core.api.score.stream.Constraint;
import ai.timefold.solver.core.api.score.stream.ConstraintCollectors;
import ai.timefold.solver.core.api.score.stream.ConstraintFactory;
import dev.sylvain.planning.domain.NiveauEffort;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;

/**
 * Soft preferences: tie-breakers that shape an otherwise valid plan — spread
 * stands across the festival for each animateur, pair beginners with a
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

    public Constraint[] define(ConstraintFactory constraintFactory) {
        return new Constraint[] {
                favoriserMixiteDesNiveaux(constraintFactory),
                equilibrerCreneauxPenibles(constraintFactory)
        };
    }

    private Constraint favoriserMixiteDesNiveaux(ConstraintFactory constraintFactory) {
        // On a slot that already has a referent, having no beginner is a missed
        // training opportunity (soft, so it never blocks a valid plan).
        return ConstraintToggleSupport.actif(constraintFactory.forEach(PosteAffectation.class), "favoriserMixiteDesNiveaux")
                .filter(poste -> poste.getAnimateur() != null)
                .groupBy(PosteAffectation::getStand,
                        PosteAffectation::getCreneau,
                        ConstraintCollectors.sum(poste -> poste.getAnimateur().estReferentPour(poste.getStand()) ? 1 : 0),
                        ConstraintCollectors.sum(poste -> poste.getAnimateur().estDebutantPour(poste.getStand()) ? 1 : 0))
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
        return ConstraintToggleSupport.actif(constraintFactory.forEach(PosteAffectation.class),
                "equilibrerCreneauxPenibles")
                .filter(poste -> poste.getAnimateur() != null && estPenible(poste.getStand()))
                .groupBy(ConstraintCollectors.loadBalance(PosteAffectation::getAnimateur))
                .penalize(HardMediumSoftScore.ONE_SOFT,
                        loadBalance -> loadBalance.unfairness()
                                .multiply(UNFAIRNESS_SCALE)
                                .setScale(0, RoundingMode.HALF_UP)
                                .intValue())
                .asConstraint("equilibrerCreneauxPenibles");
    }

    private boolean estPenible(Stand stand) {
        return stand != null && (stand.getNiveauEffort() == NiveauEffort.EPUISANT || stand.isPremium());
    }
}
