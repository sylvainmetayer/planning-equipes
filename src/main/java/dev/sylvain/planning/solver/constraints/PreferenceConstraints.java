package dev.sylvain.planning.solver.constraints;

import ai.timefold.solver.core.api.score.buildin.hardmediumsoft.HardMediumSoftScore;
import ai.timefold.solver.core.api.score.stream.Constraint;
import ai.timefold.solver.core.api.score.stream.ConstraintCollectors;
import ai.timefold.solver.core.api.score.stream.ConstraintFactory;
import ai.timefold.solver.core.api.score.stream.Joiners;
import dev.sylvain.planning.domain.PosteAffectation;

/**
 * Soft preferences: tie-breakers that shape an otherwise valid plan — spread
 * stands across the festival for each animateur and pair beginners with a
 * referent for on-the-job training.
 */
public final class PreferenceConstraints {

    public Constraint[] define(ConstraintFactory constraintFactory) {
        return new Constraint[] {
                favoriserRotationDesStands(constraintFactory),
                favoriserMixiteDesNiveaux(constraintFactory)
        };
    }

    private Constraint favoriserRotationDesStands(ConstraintFactory constraintFactory) {
        return constraintFactory.forEachUniquePair(
                PosteAffectation.class,
                Joiners.equal(PosteAffectation::getAnimateur),
                Joiners.equal(poste -> poste.getStand().getId()))
                .filter((posteA, posteB) -> posteA.getAnimateur() != null)
                .penalize(HardMediumSoftScore.ONE_SOFT)
                .asConstraint("favoriserRotationDesStands");
    }

    private Constraint favoriserMixiteDesNiveaux(ConstraintFactory constraintFactory) {
        // On a slot that already has a referent, having no beginner is a missed
        // training opportunity (soft, so it never blocks a valid plan).
        return constraintFactory.forEach(PosteAffectation.class)
                .filter(poste -> poste.getAnimateur() != null)
                .groupBy(PosteAffectation::getStand,
                        PosteAffectation::getCreneau,
                        ConstraintCollectors.sum(poste -> poste.getAnimateur().estReferentPour(poste.getStand()) ? 1 : 0),
                        ConstraintCollectors.sum(poste -> poste.getAnimateur().estDebutantPour(poste.getStand()) ? 1 : 0))
                .filter((stand, creneau, referents, debutants) -> referents > 0 && debutants == 0)
                .penalize(HardMediumSoftScore.ONE_SOFT)
                .asConstraint("favoriserMixiteDesNiveaux");
    }
}
