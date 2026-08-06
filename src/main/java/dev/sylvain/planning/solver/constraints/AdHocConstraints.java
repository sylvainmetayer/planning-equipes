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

    /**
     * Driven from the (few) ad hoc facts rather than from the (thousands of)
     * postes: with no INDISPONIBILITE_FORCEE recorded — the usual case — the
     * stream is empty and neither the toggle lookup nor the perimeter predicate
     * is ever evaluated.
     */
    private Constraint indisponibiliteForcee(ConstraintFactory constraintFactory) {
        return ConstraintToggleSupport.actif(constraintFactory.forEach(ContrainteAdHoc.class), "indisponibiliteForcee")
                .filter(contrainte -> contrainte.getType() == TypeContrainteAdHoc.INDISPONIBILITE_FORCEE)
                .join(PosteAffectation.class, Joiners.filtering(this::violeIndisponibiliteForcee))
                .penalize(HardMediumSoftScore.ONE_HARD)
                .asConstraint("indisponibiliteForcee");
    }

    /**
     * Also driven from the ad hoc facts, and joined on the two incompatible
     * animateur ids with indexed joiners.
     *
     * <p>The previous formulation paired every poste with every other poste of
     * the same créneau before testing them against the ad hoc facts. On
     * {@code scenario-complet.yaml} (2088 postes over 36 créneaux) that is
     * 59 508 pair tuples built and incrementally maintained on every move —
     * even though the reference data normally holds no INCOMPATIBILITE fact at
     * all. Starting from the fact and indexing on {@code animateur.id} +
     * {@code creneau.id} makes the tuple count proportional to the number of
     * recorded incompatibilities instead, and exactly zero when there is
     * none.</p>
     *
     * <p>One match per (poste of the first animateur, poste of the second
     * animateur on the same créneau) — the same count the unordered unique-pair
     * formulation produced, since the pair is generated once in the
     * (first, second) order only.</p>
     */
    private Constraint incompatibiliteAdHoc(ConstraintFactory constraintFactory) {
        return ConstraintToggleSupport.actif(constraintFactory.forEach(ContrainteAdHoc.class), "incompatibiliteAdHoc")
                .filter(AdHocConstraints::porteSurDeuxAnimateursIncompatibles)
                .join(PosteAffectation.class,
                        Joiners.equal(contrainte -> idAnimateurConcerne(contrainte, 0),
                                poste -> poste.getAnimateur().getId()))
                .join(PosteAffectation.class,
                        Joiners.equal((contrainte, postePremier) -> idAnimateurConcerne(contrainte, 1),
                                poste -> poste.getAnimateur().getId()),
                        Joiners.equal((contrainte, postePremier) -> postePremier.getCreneau(),
                                PosteAffectation::getCreneau))
                .filter((contrainte, postePremier, posteSecond) -> correspondAuPerimetre(contrainte, postePremier)
                        && correspondAuPerimetre(contrainte, posteSecond))
                .penalize(HardMediumSoftScore.ONE_HARD)
                .asConstraint("incompatibiliteAdHoc");
    }

    /**
     * True for an INCOMPATIBILITE naming two distinct, identifiable animateurs —
     * the only shape the indexed join above can be evaluated on. A malformed
     * fact (fewer than two animateurs, a null id, or the same animateur twice)
     * is ignored rather than penalising an animateur against themselves.
     */
    private static boolean porteSurDeuxAnimateursIncompatibles(ContrainteAdHoc contrainte) {
        if (contrainte.getType() != TypeContrainteAdHoc.INCOMPATIBILITE
                || contrainte.getAnimateursConcernes() == null
                || contrainte.getAnimateursConcernes().size() < 2) {
            return false;
        }
        String premier = idAnimateurConcerne(contrainte, 0);
        String second = idAnimateurConcerne(contrainte, 1);
        return premier != null && second != null && !premier.equals(second);
    }

    private static String idAnimateurConcerne(ContrainteAdHoc contrainte, int index) {
        Animateur animateur = contrainte.getAnimateursConcernes().get(index);
        return animateur == null ? null : animateur.getId();
    }

    private Constraint affectationForcee(ConstraintFactory constraintFactory) {
        return ConstraintToggleSupport.actif(constraintFactory.forEach(ContrainteAdHoc.class), "affectationForcee")
                .filter(contrainte -> contrainte.getType() == TypeContrainteAdHoc.AFFECTATION_FORCEE)
                .ifNotExists(PosteAffectation.class, Joiners.filtering(this::satisfaitAffectationForcee))
                .penalize(HardMediumSoftScore.ONE_HARD)
                .asConstraint("affectationForcee");
    }

    private boolean violeIndisponibiliteForcee(ContrainteAdHoc contrainte, PosteAffectation poste) {
        return concerneAnimateur(contrainte, poste.getAnimateur())
                && correspondAuPerimetre(contrainte, poste);
    }

    private boolean satisfaitAffectationForcee(ContrainteAdHoc contrainte, PosteAffectation poste) {
        return poste.getAnimateur() != null
                && concerneAnimateur(contrainte, poste.getAnimateur())
                && correspondAuPerimetre(contrainte, poste);
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
