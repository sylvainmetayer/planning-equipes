package dev.sylvain.planning.solver.constraints;

import ai.timefold.solver.core.api.score.buildin.hardmediumsoft.HardMediumSoftScore;
import ai.timefold.solver.core.api.score.stream.Constraint;
import ai.timefold.solver.core.api.score.stream.ConstraintFactory;
import ai.timefold.solver.core.api.score.stream.Joiners;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.PosteAffectation;

/**
 * Core assignment hard constraints: every mandatory seat must be filled by an
 * available, competent animateur, and nobody can hold two seats on the same
 * slot. These map to the documented assignment rules (effectif,
 * disponibilite, competence).
 */
public final class AffectationConstraints {

    public Constraint[] define(ConstraintFactory constraintFactory) {
        return new Constraint[] {
                posteDoitEtrePourvu(constraintFactory),
                animateurDisponible(constraintFactory),
                competenceCompatible(constraintFactory),
                pasDeDoubleAffectationSurMemeCreneau(constraintFactory)
        };
    }

    private Constraint posteDoitEtrePourvu(ConstraintFactory constraintFactory) {
        // forEach() excludes entities with a null planning variable value, so this
        // constraint (which specifically targets unassigned postes) must use
        // forEachIncludingUnassigned() to actually see them.
        return constraintFactory.forEachIncludingUnassigned(PosteAffectation.class)
                .filter(poste -> poste.getAnimateur() == null)
                .penalize(HardMediumSoftScore.ONE_HARD)
                .asConstraint("posteDoitEtrePourvu");
    }

    private Constraint animateurDisponible(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(PosteAffectation.class)
                .filter(poste -> {
                    Animateur animateur = poste.getAnimateur();
                    return animateur != null
                            && poste.getCreneau() != null
                            && poste.getCreneau().getDate() != null
                            && animateur.estIndisponibleLe(poste.getCreneau().getDate());
                })
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

    private Constraint pasDeDoubleAffectationSurMemeCreneau(ConstraintFactory constraintFactory) {
        return constraintFactory.forEachUniquePair(
                PosteAffectation.class,
                Joiners.equal(PosteAffectation::getAnimateur),
                Joiners.equal(PosteAffectation::getCreneau))
                .filter((posteA, posteB) -> posteA.getAnimateur() != null)
                .penalize(HardMediumSoftScore.ONE_HARD)
                .asConstraint("pasDeDoubleAffectationSurMemeCreneau");
    }
}
