package dev.sylvain.planning.solver.constraints;

import ai.timefold.solver.core.api.score.buildin.hardmediumsoft.HardMediumSoftScore;
import ai.timefold.solver.core.api.score.stream.Constraint;
import ai.timefold.solver.core.api.score.stream.ConstraintCollectors;
import ai.timefold.solver.core.api.score.stream.ConstraintFactory;
import ai.timefold.solver.core.api.score.stream.Joiners;
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

    /**
     * Mirrors favoriserRotationDesStands but inverted and scoped to premium
     * stands: prefer keeping the same (already-vetted) animateur on a
     * high-visibility stand across timeslots instead of rotating people
     * through it.
     *
     * <p>The premium test is applied <em>before</em> pairing, not after. The
     * previous formulation paired every poste with every other poste of the
     * same stand and only then discarded the non-premium ones: on
     * {@code scenario-complet.yaml} that is 115 596 pair tuples built and
     * incrementally maintained, of which zero survive the filter (the scenario
     * has no premium stand at all). Filtering first makes the pair count
     * proportional to the premium stands only.</p>
     *
     * <p>{@code lessThan} on the planning id reproduces
     * {@code forEachUniquePair}'s "each unordered pair exactly once" semantics,
     * so the match count is unchanged.</p>
     */
    private Constraint eviterRoulementStandsPremium(ConstraintFactory constraintFactory) {
        return ConstraintToggleSupport.actif(constraintFactory.forEach(PosteAffectation.class),
                "eviterRoulementStandsPremium")
                .filter(poste -> poste.getStand().isPremium())
                .join(PosteAffectation.class,
                        Joiners.equal(poste -> poste.getStand().getId()),
                        Joiners.lessThan(PosteAffectation::getId))
                .filter((posteA, posteB) -> !posteA.getAnimateur().equals(posteB.getAnimateur())
                        && !posteA.getCreneau().equals(posteB.getCreneau()))
                .penalize(HardMediumSoftScore.ONE_MEDIUM)
                .asConstraint("eviterRoulementStandsPremium");
    }

    /**
     * Same animateur, two back-to-back slots (same day, one ending exactly when
     * the other starts), different stands whose emplacements are far apart: the
     * switch costs a real trek across town, so it's penalised.
     *
     * <p>"Back-to-back" is expressed as indexed joiners (same day, previous
     * slot's {@code heureFin} equal to the next slot's {@code heureDebut})
     * instead of a predicate over every pair of postes an animateur holds. The
     * previous formulation built ~13 500 pair tuples on
     * {@code scenario-complet.yaml} (150 animateurs × ~14 postes each) purely to
     * discard almost all of them; the join now only produces genuinely
     * consecutive slots. Each qualifying pair still yields exactly one match,
     * generated in the (previous, next) order only.</p>
     */
    private Constraint eviterChangementEmplacementEloigne(ConstraintFactory constraintFactory) {
        return ConstraintToggleSupport.actif(constraintFactory.forEach(PosteAffectation.class),
                "eviterChangementEmplacementEloigne")
                .filter(poste -> poste.getStand() != null
                        && poste.getStand().getEmplacement() != null
                        && poste.getCreneau() != null
                        && poste.getCreneau().getHeureFin() != null)
                .join(PosteAffectation.class,
                        Joiners.equal(PosteAffectation::getAnimateur),
                        Joiners.equal(poste -> poste.getCreneau().getJour()),
                        Joiners.equal(poste -> poste.getCreneau().getHeureFin(),
                                poste -> poste.getCreneau().getHeureDebut()))
                .filter((precedent, suivant) -> !precedent.getStand().equals(suivant.getStand())
                        && emplacementsEloignes(precedent.getStand(), suivant.getStand()))
                .penalize(HardMediumSoftScore.ONE_MEDIUM)
                .asConstraint("eviterChangementEmplacementEloigne");
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
