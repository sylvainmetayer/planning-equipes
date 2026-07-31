package dev.sylvain.planning.solver.constraints;

import ai.timefold.solver.core.api.score.buildin.hardmediumsoft.HardMediumSoftScore;
import ai.timefold.solver.core.api.score.stream.Constraint;
import ai.timefold.solver.core.api.score.stream.ConstraintCollectors;
import ai.timefold.solver.core.api.score.stream.ConstraintFactory;
import ai.timefold.solver.core.api.score.stream.Joiners;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Emplacement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;

/**
 * Medium constraints: strongly penalised but non-blocking organisational
 * quality rules: referent coverage on complex stands, balanced workload, and
 * avoiding a majority of minors on a single slot.
 */
public final class QualiteConstraints {

    /**
     * Beyond this great-circle distance between two emplacements, moving an
     * animateur from one to the other between two back-to-back slots is
     * considered a costly trek rather than a short walk across the same site.
     */
    static final double DISTANCE_ELOIGNEE_METRES = 300.0;

    public Constraint[] define(ConstraintFactory constraintFactory) {
        return new Constraint[] {
                standComplexeAvecReferent(constraintFactory),
                equilibrerCharge(constraintFactory),
                repartitionMineursParCreneau(constraintFactory),
                experienceRequisePourStandsPremium(constraintFactory),
                eviterRoulementStandsPremium(constraintFactory),
                eviterChangementEmplacementEloigne(constraintFactory)
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

    private Constraint eviterChangementEmplacementEloigne(ConstraintFactory constraintFactory) {
        // Same animateur, two back-to-back slots (same day, one ending exactly
        // when the other starts), different stands whose emplacements are far
        // apart: the switch costs a real trek across town, so it's penalised.
        return constraintFactory.forEachUniquePair(
                PosteAffectation.class,
                Joiners.equal(PosteAffectation::getAnimateur))
                .filter((posteA, posteB) -> posteA.getAnimateur() != null
                        && posteA.getStand() != null && posteB.getStand() != null
                        && !posteA.getStand().equals(posteB.getStand())
                        && creneauxConsecutifs(posteA.getCreneau(), posteB.getCreneau())
                        && emplacementsEloignes(posteA.getStand(), posteB.getStand()))
                .penalize(HardMediumSoftScore.ONE_MEDIUM)
                .asConstraint("eviterChangementEmplacementEloigne");
    }

    /** True when two slots are back-to-back on the same day: one ends exactly when the other starts. */
    private boolean creneauxConsecutifs(Creneau a, Creneau b) {
        if (a == null || b == null || a.getJour() != b.getJour()
                || a.getHeureDebut() == null || a.getHeureFin() == null
                || b.getHeureDebut() == null || b.getHeureFin() == null) {
            return false;
        }
        Creneau tot = a.getHeureDebut().isBefore(b.getHeureDebut()) ? a : b;
        Creneau suivant = tot == a ? b : a;
        return tot.getHeureFin().equals(suivant.getHeureDebut());
    }

    private boolean emplacementsEloignes(Stand standA, Stand standB) {
        Emplacement emplacementA = standA.getEmplacement();
        Emplacement emplacementB = standB.getEmplacement();
        if (emplacementA == null || emplacementB == null) {
            return false;
        }
        Double distanceMetres = emplacementA.distanceMetresVers(emplacementB);
        return distanceMetres != null && distanceMetres > DISTANCE_ELOIGNEE_METRES;
    }
}
