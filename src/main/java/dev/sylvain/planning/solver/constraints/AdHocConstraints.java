package dev.sylvain.planning.solver.constraints;

import ai.timefold.solver.core.api.score.HardMediumSoftScore;
import ai.timefold.solver.core.api.score.stream.Constraint;
import ai.timefold.solver.core.api.score.stream.ConstraintFactory;
import ai.timefold.solver.core.api.score.stream.Joiners;
import ai.timefold.solver.core.api.score.stream.uni.UniConstraintStream;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.TypeContrainteAdHoc;

/**
 * One-off administrative exceptions. The prescriptive ones
 * (INDISPONIBILITE_FORCEE, INCOMPATIBILITE, AFFECTATION_FORCEE) are enforced
 * as hard constraints, at the same priority as the legal ones, so the
 * optimiser can never silently work around them. AFFINITE is the one
 * deliberate exception: a soft reward — hard, a preferred pair would be a
 * forced assignment in disguise, colliding with load balancing and individual
 * availability (issue #80).
 */
public final class AdHocConstraints {

    public Constraint[] define(ConstraintFactory constraintFactory) {
        return new Constraint[] {
            indisponibiliteForcee(constraintFactory),
            incompatibiliteAdHoc(constraintFactory),
            affectationForcee(constraintFactory),
            affiniteAdHoc(constraintFactory)
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
                .join(
                        PosteAffectation.class,
                        Joiners.filtering((contrainte, poste) ->
                                PastSeats.reproachable(poste) && violatesForcedIndisponibilite(contrainte, poste)))
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
                .filter(AdHocConstraints::coversTwoIncompatibleAnimateurs)
                .join(
                        PosteAffectation.class,
                        Joiners.equal(
                                contrainte -> concernedAnimateurId(contrainte, 0),
                                poste -> poste.getAnimateur().getId()))
                .join(
                        PosteAffectation.class,
                        Joiners.equal(
                                (contrainte, postePremier) -> concernedAnimateurId(contrainte, 1),
                                poste -> poste.getAnimateur().getId()),
                        Joiners.equal(
                                (contrainte, postePremier) -> postePremier.getCreneau(), PosteAffectation::getCreneau))
                .filter((contrainte, postePremier, posteSecond) -> PastSeats.reproachable(postePremier, posteSecond)
                        && matchesScope(contrainte, postePremier)
                        && matchesScope(contrainte, posteSecond))
                .penalize(HardMediumSoftScore.ONE_HARD)
                .asConstraint("incompatibiliteAdHoc");
    }

    /**
     * The positive counterpart of {@link #incompatibiliteAdHoc}: a soft reward
     * for every créneau where both animateurs of an AFFINITE pair hold a poste
     * on the <b>same stand</b> (within the constraint's optional
     * créneau/stand perimeter). Reward rather than penalty: penalising the
     * pair's absence would punish every créneau where one of the two simply
     * does not work — permanent noise in the score (issue #80). Same
     * fact-driven indexed-join shape as {@link #incompatibiliteAdHoc}, so the
     * tuple count stays proportional to the number of recorded affinités and
     * is exactly zero when there is none.
     */
    private Constraint affiniteAdHoc(ConstraintFactory constraintFactory) {
        return ConstraintToggleSupport.actif(constraintFactory.forEach(ContrainteAdHoc.class), "affiniteAdHoc")
                .filter(AdHocConstraints::coversAffinityPair)
                .join(
                        PosteAffectation.class,
                        Joiners.equal(
                                contrainte -> concernedAnimateurId(contrainte, 0),
                                poste -> poste.getAnimateur().getId()))
                .join(
                        PosteAffectation.class,
                        Joiners.equal(
                                (contrainte, postePremier) -> concernedAnimateurId(contrainte, 1),
                                poste -> poste.getAnimateur().getId()),
                        Joiners.equal(
                                (contrainte, postePremier) -> postePremier.getCreneau(), PosteAffectation::getCreneau))
                // The same past rule as the penalties (ADR 0044): a reward on
                // a pair already worked is a constant no move can act on, and
                // it would only inflate the score the analyses compare.
                .filter((contrainte, postePremier, posteSecond) -> PastSeats.reproachable(postePremier, posteSecond)
                        && surMemeStand(postePremier, posteSecond)
                        && matchesScope(contrainte, postePremier)
                        && matchesScope(contrainte, posteSecond))
                .reward(HardMediumSoftScore.ONE_SOFT)
                .asConstraint("affiniteAdHoc");
    }

    /**
     * True for an INCOMPATIBILITE naming two distinct, identifiable animateurs —
     * the only shape the indexed join above can be evaluated on. A malformed
     * fact (fewer than two animateurs, a null id, or the same animateur twice)
     * is ignored rather than penalising an animateur against themselves.
     */
    private static boolean coversTwoIncompatibleAnimateurs(ContrainteAdHoc contrainte) {
        return coversIdentifiablePair(contrainte, TypeContrainteAdHoc.INCOMPATIBILITE);
    }

    /** Same well-formedness gate as the incompatibilité, for AFFINITE facts. */
    private static boolean coversAffinityPair(ContrainteAdHoc contrainte) {
        return coversIdentifiablePair(contrainte, TypeContrainteAdHoc.AFFINITE);
    }

    private static boolean coversIdentifiablePair(ContrainteAdHoc contrainte, TypeContrainteAdHoc type) {
        if (contrainte.getType() != type
                || contrainte.getAnimateursConcernes() == null
                || contrainte.getAnimateursConcernes().size() < 2) {
            return false;
        }
        String premier = concernedAnimateurId(contrainte, 0);
        String second = concernedAnimateurId(contrainte, 1);
        return premier != null && second != null && !premier.equals(second);
    }

    private static boolean surMemeStand(PosteAffectation postePremier, PosteAffectation posteSecond) {
        return postePremier.getStand() != null
                && posteSecond.getStand() != null
                && postePremier.getStand().getId().equals(posteSecond.getStand().getId());
    }

    private static String concernedAnimateurId(ContrainteAdHoc contrainte, int index) {
        Animateur animateur = contrainte.getAnimateursConcernes().get(index);
        return animateur == null ? null : animateur.getId();
    }

    /**
     * Unsatisfied while a seat of its scope is still ahead of now — or while
     * its scope holds no seat at all, which is what a forced assignment on a
     * vacation the grid does not carry looks like, and stays a violation to
     * read on the Problèmes page. What is <em>not</em> charged (ADR 0044) is
     * the third case, a scope whose every seat is past: a forced assignment
     * on a timeslot already worked without it is history, not a hole the
     * solver can fill. That case is the {@link #forcedAssignmentsInThePast}
     * stream, excluded by an {@code ifNotExists} on the fact itself.
     */
    private Constraint affectationForcee(ConstraintFactory constraintFactory) {
        return ConstraintToggleSupport.actif(constraintFactory.forEach(ContrainteAdHoc.class), "affectationForcee")
                .filter(contrainte -> contrainte.getType() == TypeContrainteAdHoc.AFFECTATION_FORCEE)
                .ifNotExists(forcedAssignmentsInThePast(constraintFactory), Joiners.equal(ContrainteAdHoc::getId))
                .ifNotExists(PosteAffectation.class, Joiners.filtering(this::satisfiesForcedAffectation))
                .penalize(HardMediumSoftScore.ONE_HARD)
                .asConstraint("affectationForcee");
    }

    /** The forced assignments whose scope holds seats, every one of them past. */
    private static UniConstraintStream<ContrainteAdHoc> forcedAssignmentsInThePast(
            ConstraintFactory constraintFactory) {
        return constraintFactory
                .forEach(ContrainteAdHoc.class)
                .filter(contrainte -> contrainte.getType() == TypeContrainteAdHoc.AFFECTATION_FORCEE)
                .ifExistsIncludingUnassigned(
                        PosteAffectation.class,
                        Joiners.filtering((contrainte, poste) -> poste.isPasse() && matchesScope(contrainte, poste)))
                .ifNotExistsIncludingUnassigned(
                        PosteAffectation.class,
                        Joiners.filtering((contrainte, poste) ->
                                PastSeats.reproachable(poste) && matchesScope(contrainte, poste)));
    }

    /**
     * Whether this seat, as currently staffed, breaks that forced
     * unavailability. Public and static because the incremental reconciliation
     * (issue #86) asks the same question outside the solver: a seat it would
     * pin must not carry a violation nobody can then fix — and the two must
     * never drift apart, hence one implementation.
     */
    public static boolean violatesForcedIndisponibilite(ContrainteAdHoc contrainte, PosteAffectation poste) {
        return concernsAnimateur(contrainte, poste.getAnimateur()) && matchesScope(contrainte, poste);
    }

    private boolean satisfiesForcedAffectation(ContrainteAdHoc contrainte, PosteAffectation poste) {
        return poste.getAnimateur() != null
                && concernsAnimateur(contrainte, poste.getAnimateur())
                && matchesScope(contrainte, poste);
    }

    private static boolean concernsAnimateur(ContrainteAdHoc contrainte, Animateur animateur) {
        return contrainte.getAnimateursConcernes() != null
                && contrainte.getAnimateursConcernes().stream()
                        .anyMatch(target -> correspondAnimateur(target, animateur));
    }

    private static boolean correspondAnimateur(Animateur expected, Animateur actual) {
        return expected != null
                && actual != null
                && expected.getId() != null
                && expected.getId().equals(actual.getId());
    }

    /**
     * Whether the seat falls inside the constraint's scope.
     *
     * <p>A scope naming a créneau the grid no longer holds matches
     * <b>nothing</b> (issue #577). The repository reads the id back by joining
     * on the vacation's natural key, so it comes back {@code null} while that
     * vacation is absent: a rule written for one slot must not quietly become
     * a rule for the whole edition because the grid was regenerated.</p>
     */
    private static boolean matchesScope(ContrainteAdHoc contrainte, PosteAffectation poste) {
        boolean creneauOk = contrainte.getCreneau() == null
                || (poste.getCreneau() != null
                        && contrainte.getCreneau().getId() != null
                        && contrainte
                                .getCreneau()
                                .getId()
                                .equals(poste.getCreneau().getId()));
        boolean standOk = contrainte.getStand() == null
                || (poste.getStand() != null
                        && contrainte.getStand().getId().equals(poste.getStand().getId()));
        return creneauOk && standOk;
    }
}
