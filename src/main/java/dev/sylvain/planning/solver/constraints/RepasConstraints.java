package dev.sylvain.planning.solver.constraints;

import ai.timefold.solver.core.api.score.HardMediumSoftScore;
import ai.timefold.solver.core.api.score.stream.Constraint;
import ai.timefold.solver.core.api.score.stream.ConstraintCollectors;
import ai.timefold.solver.core.api.score.stream.ConstraintFactory;
import dev.sylvain.planning.domain.CoupureRepas;
import dev.sylvain.planning.domain.FenetreRepas;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.PosteAffectation;

/**
 * The meal break: whoever works either side of a meal window gets one, and
 * gets it as early as the window allows.
 *
 * <p><b>This is not a rule of the Code du travail.</b> The only break the Code
 * imposes is art. L3121-16 — twenty consecutive minutes once an adult's working
 * time reaches six hours — and {@code travailContinuMaxMajeur} carries it. The
 * meal break is the organiser's own rule, the one the staffing workbook's midday
 * and evening rotations are cut for. It is catalogued under
 * « Organisation (repas) », protected all the same: the solver can otherwise
 * return a ten-hour unbroken day scoring zero hard, and nothing in the score
 * says so.</p>
 *
 * <p>Two consequences follow from the two being distinct objects, and both are
 * the point of issue #438:</p>
 * <ul>
 * <li><b>Independent of {@link ParametresLegaux#isPauseSurPoste()}.</b>
 * Declaring the legal break taken on the post, by relay between colleagues,
 * says the twenty minutes happen inside the vacation. It says nothing about
 * lunch. The parameter that neutralises the first must not carry away the
 * second — which is exactly how a 10:00-20:00 day passed unnoticed.</li>
 * <li><b>Independent of how the grid was built.</b> The meal windows used to
 * be read only when a day-long amplitude was sliced into vacations by the
 * découpage; a grid typed by hand was never sliced, so nothing ever looked at
 * them. They travel as {@link FenetreRepas} facts now, and the découpage that
 * was their only reader is gone.</li>
 * </ul>
 *
 * <p>Grouped per animateur <i>and date</i>, like the daily legal caps, so each
 * group holds a handful of seats; the window is then joined, which is what
 * makes a day straddling midday <i>and</i> evening produce one violation per
 * window rather than one aggregate nobody can act on.</p>
 *
 * @see CoupureRepas for what is owed, what satisfies it, and why
 */
public final class RepasConstraints {

    public Constraint[] define(ConstraintFactory constraintFactory) {
        return new Constraint[] {
            coupureRepasObligatoire(constraintFactory), coupureRepasPlacementPrefere(constraintFactory)
        };
    }

    /**
     * A day worked either side of a meal window must leave the break free
     * inside it.
     *
     * <p>Penalised by the <b>minutes missing</b> from the longest free stretch,
     * not by one point per faulty day: a day fifteen minutes short and a day
     * that never stops are not the same problem, and a flat penalty would leave
     * the solver a cliff instead of a slope to walk down.</p>
     *
     * <p>On the reported case (issue #438: seats 10-12, 12-13, 13-14 and 14-20,
     * midday window 12:00-14:00 owing 60 minutes) the day starts before noon,
     * ends after two, and leaves no hole at all: 60 hard.</p>
     *
     * <p><b>A grid can make this unsatisfiable.</b> Where a single créneau
     * spans the whole window — a 10:00-20:00 vacation in one block — its holder
     * cannot step away, and {@code posteDoitEtrePourvu} still demands the seat
     * be filled. No assignment then scores zero hard, and the answer is to
     * re-cut the grid, or to switch this rule off from the Contraintes screen.
     * See {@code docs/contraintes.md}.</p>
     */
    private Constraint coupureRepasObligatoire(ConstraintFactory constraintFactory) {
        return ConstraintToggleSupport.actif(
                        constraintFactory.forEach(PosteAffectation.class), "coupureRepasObligatoire")
                .filter(RepasConstraints::exploitable)
                .groupBy(
                        PosteAffectation::getAnimateur,
                        poste -> poste.getCreneau().getDate(),
                        ConstraintCollectors.toList())
                .join(FenetreRepas.class)
                .filter((animateur, date, postes, fenetre) ->
                        CoupureRepas.of(postes, fenetre).manquante())
                .penalize(
                        HardMediumSoftScore.ONE_HARD,
                        (animateur, date, postes, fenetre) ->
                                CoupureRepas.of(postes, fenetre).minutesManquantes())
                .asConstraint("coupureRepasObligatoire");
    }

    /**
     * Of the slots a meal window offers, the one the window points to is
     * preferred — <b>late at midday, early in the evening</b>.
     *
     * <p>The rule used to prefer the earliest slot in both windows, and cited
     * the organiser for it: « soit 12-13, soit 13-14, avec une préférence pour
     * 12-13 ». The point of 14/09 reversed the midday half (issue #596): the
     * stands have just opened at noon, so lunch is taken at the end of its
     * window; dinner is taken at the start of its own, so the stands reopen.
     * The evening half was already right, which is why the rule was half
     * correct rather than wrong.</p>
     *
     * <p>Penalised by how many minutes the break sits away from the end the
     * window points to, so the preferred slot costs nothing and the other one
     * costs its own offset. Everyone taking the same slot would empty the
     * stands, but that is not this rule's problem to solve: seat coverage is
     * hard, this is soft, and the arbitration between them is what spreads the
     * rotation.</p>
     *
     * <p>Silent when no break fits: {@link #coupureRepasObligatoire} is
     * already carrying that day, and a preference has nothing to say about a
     * break that does not exist. Silent too when the window leaves enough room
     * to satisfy it — a day leaving the whole midday free is not « eats at
     * noon », it is a day where the person picks, and
     * {@link CoupureRepas#avanceMinutes()} reads what they could pick.</p>
     */
    private Constraint coupureRepasPlacementPrefere(ConstraintFactory constraintFactory) {
        return ConstraintToggleSupport.actif(
                        constraintFactory.forEach(PosteAffectation.class), "coupureRepasPlacementPrefere")
                .filter(RepasConstraints::exploitable)
                .groupBy(
                        PosteAffectation::getAnimateur,
                        poste -> poste.getCreneau().getDate(),
                        ConstraintCollectors.toList())
                .join(FenetreRepas.class)
                .filter((animateur, date, postes, fenetre) ->
                        CoupureRepas.of(postes, fenetre).preferredSlotGapMinutes() > 0)
                .penalize(
                        HardMediumSoftScore.ONE_SOFT,
                        (animateur, date, postes, fenetre) ->
                                CoupureRepas.of(postes, fenetre).preferredSlotGapMinutes())
                .asConstraint("coupureRepasPlacementPrefere");
    }

    /** A seat someone holds, on a dated créneau whose hours are known. */
    private static boolean exploitable(PosteAffectation poste) {
        return poste.getAnimateur() != null
                && poste.getCreneau() != null
                && poste.getCreneau().getDate() != null
                && poste.heureDebutEffectif() != null;
    }
}
