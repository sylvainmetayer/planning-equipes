package dev.sylvain.planning.solver.constraints;

import ai.timefold.solver.core.api.score.buildin.hardmediumsoft.HardMediumSoftScore;
import ai.timefold.solver.core.api.score.stream.Constraint;
import ai.timefold.solver.core.api.score.stream.ConstraintCollectors;
import ai.timefold.solver.core.api.score.stream.ConstraintFactory;
import ai.timefold.solver.core.api.score.stream.Joiners;
import dev.sylvain.planning.domain.PosteAffectation;

/**
 * Medium constraints: strongly penalised but non-blocking organisational
 * quality rules: referent coverage on complex stands, balanced workload, and
 * avoiding a majority of minors on a single slot.
 */
public final class QualiteConstraints {

    public Constraint[] define(ConstraintFactory constraintFactory) {
        return new Constraint[] {
                standComplexeAvecReferent(constraintFactory),
                equilibrerCharge(constraintFactory),
                repartitionMineursParCreneau(constraintFactory),
                experienceRequisePourStandsPremium(constraintFactory),
                eviterRoulementStandsPremium(constraintFactory)
        };
    }

    private Constraint standComplexeAvecReferent(ConstraintFactory constraintFactory) {
        return ConstraintToggleSupport.actif(constraintFactory.forEach(PosteAffectation.class),
                "standComplexeAvecReferent")
                .groupBy(PosteAffectation::getStand,
                        PosteAffectation::getCreneau,
                        ConstraintCollectors.sum(poste -> poste.getAnimateur() != null
                                && poste.getAnimateur().estReferentPour(poste.getStand()) ? 1 : 0))
                .filter((stand, creneau, nombreReferents) -> nombreReferents == 0)
                .penalize(HardMediumSoftScore.ONE_MEDIUM)
                .asConstraint("standComplexeAvecReferent");
    }

    private Constraint equilibrerCharge(ConstraintFactory constraintFactory) {
        // loadBalance().unfairness() is 0 when every animateur carries the same
        // number of postes and grows with the deviation, giving a clean fairness
        // signal without the huge non-zero baseline of a sum-of-squares formula.
        return ConstraintToggleSupport.actif(constraintFactory.forEach(PosteAffectation.class), "equilibrerCharge")
                .filter(poste -> poste.getAnimateur() != null)
                .groupBy(ConstraintCollectors.loadBalance(PosteAffectation::getAnimateur))
                .penalize(HardMediumSoftScore.ONE_MEDIUM,
                        loadBalance -> loadBalance.unfairness().intValue())
                .asConstraint("equilibrerCharge");
    }

    private Constraint repartitionMineursParCreneau(ConstraintFactory constraintFactory) {
        return ConstraintToggleSupport.actif(constraintFactory.forEach(PosteAffectation.class),
                "repartitionMineursParCreneau")
                .filter(poste -> poste.getAnimateur() != null && poste.getCreneau() != null)
                .groupBy(PosteAffectation::getStand,
                        PosteAffectation::getCreneau,
                        ConstraintCollectors.sum(poste -> poste.getAnimateur()
                                .estMineurLe(poste.getCreneau().getDate()) ? 1 : 0),
                        ConstraintCollectors.sum(poste -> poste.getAnimateur()
                                .estMajeurLe(poste.getCreneau().getDate()) ? 1 : 0))
                .filter((stand, creneau, mineurs, majeurs) -> mineurs > majeurs)
                .penalize(HardMediumSoftScore.ONE_MEDIUM,
                        (stand, creneau, mineurs, majeurs) -> mineurs - majeurs)
                .asConstraint("repartitionMineursParCreneau");
    }

    private Constraint experienceRequisePourStandsPremium(ConstraintFactory constraintFactory) {
        return ConstraintToggleSupport.actif(constraintFactory.forEach(PosteAffectation.class),
                "experienceRequisePourStandsPremium")
                .filter(poste -> poste.getStand().isPremium()
                        && poste.getAnimateur() != null
                        && poste.getAnimateur().estDebutantPour(poste.getStand()))
                .penalize(HardMediumSoftScore.ONE_MEDIUM)
                .asConstraint("experienceRequisePourStandsPremium");
    }

    private Constraint eviterRoulementStandsPremium(ConstraintFactory constraintFactory) {
        // Mirrors favoriserRotationDesStands but inverted and scoped to premium
        // stands: prefer keeping the same (already-vetted) animateur on a
        // high-visibility stand across timeslots instead of rotating people
        // through it.
        return ConstraintToggleSupport.actif(constraintFactory.forEachUniquePair(
                PosteAffectation.class,
                Joiners.equal(poste -> poste.getStand().getId())), "eviterRoulementStandsPremium")
                .filter((posteA, posteB) -> posteA.getStand().isPremium()
                        && posteA.getAnimateur() != null
                        && posteB.getAnimateur() != null
                        && !posteA.getAnimateur().equals(posteB.getAnimateur())
                        && !posteA.getCreneau().equals(posteB.getCreneau()))
                .penalize(HardMediumSoftScore.ONE_MEDIUM)
                .asConstraint("eviterRoulementStandsPremium");
    }
}
