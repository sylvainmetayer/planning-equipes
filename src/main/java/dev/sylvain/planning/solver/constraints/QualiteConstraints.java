package dev.sylvain.planning.solver.constraints;

import ai.timefold.solver.core.api.score.buildin.hardmediumsoft.HardMediumSoftScore;
import ai.timefold.solver.core.api.score.stream.Constraint;
import ai.timefold.solver.core.api.score.stream.ConstraintCollectors;
import ai.timefold.solver.core.api.score.stream.ConstraintFactory;
import dev.sylvain.planning.domain.PosteAffectation;

/**
 * Medium constraints: strongly penalised but non-blocking organisational
 * quality rules (cahier des charges 4.2, 4.3, 4.4) — referent coverage on
 * complex stands, balanced workload, and avoiding a majority of minors on a
 * single slot.
 */
public final class QualiteConstraints {

    public Constraint[] define(ConstraintFactory constraintFactory) {
        return new Constraint[] {
                standComplexeAvecReferent(constraintFactory),
                equilibrerCharge(constraintFactory),
                repartitionMineursParCreneau(constraintFactory)
        };
    }

    private Constraint standComplexeAvecReferent(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(PosteAffectation.class)
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
        return constraintFactory.forEach(PosteAffectation.class)
                .filter(poste -> poste.getAnimateur() != null)
                .groupBy(ConstraintCollectors.loadBalance(PosteAffectation::getAnimateur))
                .penalize(HardMediumSoftScore.ONE_MEDIUM,
                        loadBalance -> loadBalance.unfairness().intValue())
                .asConstraint("equilibrerCharge");
    }

    private Constraint repartitionMineursParCreneau(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(PosteAffectation.class)
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
}
