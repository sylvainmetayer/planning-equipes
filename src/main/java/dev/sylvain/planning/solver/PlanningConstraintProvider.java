package dev.sylvain.planning.solver;

import ai.timefold.solver.core.api.score.buildin.hardmediumsoft.HardMediumSoftScore;
import ai.timefold.solver.core.api.score.stream.Constraint;
import ai.timefold.solver.core.api.score.stream.ConstraintCollectors;
import ai.timefold.solver.core.api.score.stream.ConstraintFactory;
import ai.timefold.solver.core.api.score.stream.ConstraintProvider;
import ai.timefold.solver.core.api.score.stream.Joiners;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.TypeContrainteAdHoc;

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
                indisponibiliteForcee(constraintFactory),
                incompatibiliteAdHoc(constraintFactory),
                affectationForcee(constraintFactory),
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

    private Constraint indisponibiliteForcee(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(PosteAffectation.class)
                .filter(poste -> poste.getAnimateur() != null)
                .join(ContrainteAdHoc.class, Joiners.filtering(this::violeIndisponibiliteForcee))
                .penalize(HardMediumSoftScore.ONE_HARD)
                .asConstraint("indisponibiliteForcee");
    }

    private Constraint incompatibiliteAdHoc(ConstraintFactory constraintFactory) {
        return constraintFactory.forEachUniquePair(
                PosteAffectation.class,
                Joiners.equal(PosteAffectation::getCreneau))
                .filter((posteA, posteB) -> posteA.getAnimateur() != null
                        && posteB.getAnimateur() != null
                        && !posteA.getAnimateur().equals(posteB.getAnimateur()))
                .join(ContrainteAdHoc.class, Joiners.filtering(this::violeIncompatibilite))
                .penalize(HardMediumSoftScore.ONE_HARD)
                .asConstraint("incompatibiliteAdHoc");
    }

    private Constraint affectationForcee(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(ContrainteAdHoc.class)
                .filter(contrainte -> contrainte.getType() == TypeContrainteAdHoc.AFFECTATION_FORCEE)
                .ifNotExists(PosteAffectation.class, Joiners.filtering(this::satisfaitAffectationForcee))
                .penalize(HardMediumSoftScore.ONE_HARD)
                .asConstraint("affectationForcee");
    }

    private boolean violeIndisponibiliteForcee(PosteAffectation poste, ContrainteAdHoc contrainte) {
        return contrainte.getType() == TypeContrainteAdHoc.INDISPONIBILITE_FORCEE
                && concerneAnimateur(contrainte, poste.getAnimateur())
                && correspondAuPerimetre(contrainte, poste);
    }

    private boolean violeIncompatibilite(PosteAffectation posteA, PosteAffectation posteB, ContrainteAdHoc contrainte) {
        return contrainte.getType() == TypeContrainteAdHoc.INCOMPATIBILITE
                && contrainte.getAnimateursConcernes() != null
                && contrainte.getAnimateursConcernes().size() >= 2
                && sontLesMemeAnimateurs(contrainte, posteA.getAnimateur(), posteB.getAnimateur())
                && correspondAuPerimetre(contrainte, posteA)
                && correspondAuPerimetre(contrainte, posteB);
    }

    private boolean satisfaitAffectationForcee(ContrainteAdHoc contrainte, PosteAffectation poste) {
        return poste.getAnimateur() != null
                && concerneAnimateur(contrainte, poste.getAnimateur())
                && correspondAuPerimetre(contrainte, poste);
    }

    private boolean sontLesMemeAnimateurs(ContrainteAdHoc contrainte, Animateur a, Animateur b) {
        Animateur premier = contrainte.getAnimateursConcernes().get(0);
        Animateur second = contrainte.getAnimateursConcernes().get(1);
        return (correspondAnimateur(premier, a) && correspondAnimateur(second, b))
                || (correspondAnimateur(premier, b) && correspondAnimateur(second, a));
    }

    private boolean concerneAnimateur(ContrainteAdHoc contrainte, Animateur animateur) {
        return contrainte.getAnimateursConcernes() != null
                && contrainte.getAnimateursConcernes().stream().anyMatch(cible -> correspondAnimateur(cible, animateur));
    }

    private boolean correspondAnimateur(Animateur expected, Animateur actual) {
        return expected != null
                && actual != null
                && expected.getId() != null
                && expected.getId().equals(actual.getId());
    }

    private boolean correspondAuPerimetre(ContrainteAdHoc contrainte, PosteAffectation poste) {
        boolean creneauOk = contrainte.getCreneau() == null
                || (poste.getCreneau() != null && contrainte.getCreneau().getId().equals(poste.getCreneau().getId()));
        boolean standOk = contrainte.getStand() == null
                || (poste.getStand() != null && contrainte.getStand().getId().equals(poste.getStand().getId()));
        return creneauOk && standOk;
    }
}
