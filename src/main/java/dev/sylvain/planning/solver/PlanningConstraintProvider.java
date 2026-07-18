package dev.sylvain.planning.solver;

import ai.timefold.solver.core.api.score.buildin.hardmediumsoft.HardMediumSoftScore;
import ai.timefold.solver.core.api.score.stream.Constraint;
import ai.timefold.solver.core.api.score.stream.ConstraintCollectors;
import ai.timefold.solver.core.api.score.stream.ConstraintFactory;
import ai.timefold.solver.core.api.score.stream.ConstraintProvider;
import ai.timefold.solver.core.api.score.stream.Joiners;
import dev.sylvain.planning.domain.PosteAffectation;

public class PlanningConstraintProvider implements ConstraintProvider {

    @Override
    public Constraint[] defineConstraints(ConstraintFactory constraintFactory) {
        return new Constraint[] {
                posteDoitEtrePourvu(constraintFactory),
                animateurDisponible(constraintFactory),
                competenceCompatible(constraintFactory),
                standReserveAuxMajeurs(constraintFactory),
                pasDeDoubleAffectationSurMemeCreneau(constraintFactory),
                mineurNecessiteEncadrementMajeur(constraintFactory),
                standComplexeAvecReferent(constraintFactory),
                equilibrerCharge(constraintFactory),
                favoriserRotationDesStands(constraintFactory)
        };
    }

    private Constraint posteDoitEtrePourvu(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(PosteAffectation.class)
                .filter(poste -> poste.getAnimateur() == null)
                .penalize(HardMediumSoftScore.ONE_HARD)
                .asConstraint("posteDoitEtrePourvu");
    }

    private Constraint animateurDisponible(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(PosteAffectation.class)
                .filter(poste -> poste.getAnimateur() != null
                        && !poste.getAnimateur().getDisponibilites().contains(poste.getCreneau()))
                .penalize(HardMediumSoftScore.ONE_HARD)
                .asConstraint("animateurDisponible");
    }

    private Constraint competenceCompatible(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(PosteAffectation.class)
                .filter(poste -> poste.getAnimateur() != null
                        && !poste.getAnimateur().possedeCompetencePour(poste.getStand()))
                .penalize(HardMediumSoftScore.ONE_HARD)
                .asConstraint("competenceCompatible");
    }

    private Constraint standReserveAuxMajeurs(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(PosteAffectation.class)
                .filter(poste -> poste.getAnimateur() != null
                        && poste.getStand().isReserveMajeurs()
                        && poste.getAnimateur().estMineurLe(poste.getCreneau().getDate()))
                .penalize(HardMediumSoftScore.ONE_HARD)
                .asConstraint("standReserveAuxMajeurs");
    }

    private Constraint pasDeDoubleAffectationSurMemeCreneau(ConstraintFactory constraintFactory) {
        return constraintFactory.forEachUniquePair(
                PosteAffectation.class,
                Joiners.equal(PosteAffectation::getAnimateur),
                Joiners.equal(PosteAffectation::getCreneau))
                .filter((posteA, posteB) -> posteA.getAnimateur() != null)
                .penalize(HardMediumSoftScore.ONE_HARD)
                .asConstraint("pasDeDoubleAffectationSurMemeCreneau");
    }

    private Constraint mineurNecessiteEncadrementMajeur(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(PosteAffectation.class)
                .filter(poste -> poste.getAnimateur() != null
                        && poste.getAnimateur().estMineurLe(poste.getCreneau().getDate()))
                .ifNotExists(PosteAffectation.class,
                        Joiners.equal(PosteAffectation::getStand),
                        Joiners.equal(PosteAffectation::getCreneau),
                        Joiners.filtering((posteMineur, autrePoste) -> autrePoste.getAnimateur() != null
                                && autrePoste.getAnimateur().estMajeurLe(autrePoste.getCreneau().getDate())))
                .penalize(HardMediumSoftScore.ONE_HARD)
                .asConstraint("mineurNecessiteEncadrementMajeur");
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
        return constraintFactory.forEach(PosteAffectation.class)
                .filter(poste -> poste.getAnimateur() != null)
                .groupBy(PosteAffectation::getAnimateur, ConstraintCollectors.count())
                .penalize(HardMediumSoftScore.ONE_MEDIUM, (animateur, nbAffectations) -> nbAffectations * nbAffectations)
                .asConstraint("equilibrerCharge");
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
}
