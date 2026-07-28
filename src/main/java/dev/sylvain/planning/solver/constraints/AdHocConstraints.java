package dev.sylvain.planning.solver.constraints;

import ai.timefold.solver.core.api.score.buildin.hardmediumsoft.HardMediumSoftScore;
import ai.timefold.solver.core.api.score.stream.Constraint;
import ai.timefold.solver.core.api.score.stream.ConstraintFactory;
import ai.timefold.solver.core.api.score.stream.Joiners;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.TypeContrainteAdHoc;

/**
 * One-off administrative exceptions. They are enforced as hard constraints, at
 * the same priority as the legal ones, so the optimiser can never silently
 * work around them.
 */
public final class AdHocConstraints {

    public Constraint[] define(ConstraintFactory constraintFactory) {
        return new Constraint[] {
                indisponibiliteForcee(constraintFactory),
                incompatibiliteAdHoc(constraintFactory),
                affectationForcee(constraintFactory)
        };
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
