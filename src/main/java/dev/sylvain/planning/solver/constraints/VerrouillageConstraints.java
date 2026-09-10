package dev.sylvain.planning.solver.constraints;

import ai.timefold.solver.core.api.score.HardMediumSoftScore;
import ai.timefold.solver.core.api.score.stream.Constraint;
import ai.timefold.solver.core.api.score.stream.ConstraintFactory;
import ai.timefold.solver.core.api.score.stream.Joiners;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.TypeVerrouillage;
import dev.sylvain.planning.domain.VerrouillagePlanning;

/**
 * Partial planning locks (issue #87). Freezing a day, a stand or a créneau
 * needs no constraint at all: the covered seats are pinned before the solve
 * ({@code PosteAffectation.verrouille}), so no move can touch them.
 *
 * <p>Freezing an <b>animateur</b> needs this one in addition. Pinning only
 * freezes the seats they already hold; without a rule, the solver would still
 * be free to hand them an extra seat elsewhere — their planning would have
 * moved, which is exactly what the lock says must not happen.</p>
 */
public final class VerrouillageConstraints {

    public Constraint[] define(ConstraintFactory constraintFactory) {
        return new Constraint[] {
            animateurVerrouilleFige(constraintFactory), animateurVerrouilleCreneauFige(constraintFactory)
        };
    }

    /**
     * Driven from the (few) lock facts and indexed on the animateur id, like
     * {@code AdHocConstraints.incompatibiliteAdHoc}: with no ANIMATEUR lock
     * recorded — the usual case — the stream is empty and no poste tuple is
     * ever built.
     *
     * <p>Only unpinned seats are penalised: the frozen animateur's own,
     * validated seats are pinned, and penalising those would put a permanent
     * hard violation in every plan.</p>
     */
    private Constraint animateurVerrouilleFige(ConstraintFactory constraintFactory) {
        return ConstraintToggleSupport.actif(
                        constraintFactory.forEach(VerrouillagePlanning.class), "animateurVerrouilleFige")
                .filter(verrouillage ->
                        verrouillage.getType() == TypeVerrouillage.ANIMATEUR && verrouillage.getAnimateurId() != null)
                .join(
                        PosteAffectation.class,
                        Joiners.equal(
                                VerrouillagePlanning::getAnimateurId,
                                poste -> poste.getAnimateur().getId()))
                .filter((verrouillage, poste) -> !poste.isVerrouille())
                .penalize(HardMediumSoftScore.ONE_HARD)
                .asConstraint("animateurVerrouilleFige");
    }

    /**
     * Same mechanism as {@link #animateurVerrouilleFige}, narrowed to one
     * créneau: an {@code ANIMATEUR_CRENEAU} lock (posed when a demande
     * d'échange is validated, issue #165) freezes what that animateur holds on
     * that créneau — the swapped seat itself is pinned — and forbids the solver
     * to hand them another, unpinned seat there. The animateur freed by a
     * one-way takeover is kept free the same way: nothing of theirs is pinned
     * on the créneau, so any new seat there is a violation.
     */
    private Constraint animateurVerrouilleCreneauFige(ConstraintFactory constraintFactory) {
        return ConstraintToggleSupport.actif(
                        constraintFactory.forEach(VerrouillagePlanning.class), "animateurVerrouilleCreneauFige")
                .filter(verrouillage -> verrouillage.getType() == TypeVerrouillage.ANIMATEUR_CRENEAU
                        && verrouillage.getAnimateurId() != null
                        && verrouillage.getCreneauId() != null)
                .join(
                        PosteAffectation.class,
                        Joiners.equal(
                                VerrouillagePlanning::getAnimateurId,
                                poste -> poste.getAnimateur().getId()),
                        Joiners.equal(
                                VerrouillagePlanning::getCreneauId,
                                poste -> poste.getCreneau().getId()))
                .filter((verrouillage, poste) -> !poste.isVerrouille())
                .penalize(HardMediumSoftScore.ONE_HARD)
                .asConstraint("animateurVerrouilleCreneauFige");
    }
}
