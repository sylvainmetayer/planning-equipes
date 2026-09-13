package dev.sylvain.planning.solver.constraints;

import dev.sylvain.planning.solver.EligibleAnimateurMoveFilter;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * What a seat breaking one of the eligibility exclusions costs: a day the
 * animateur declared off, a minor at night, on an adults-only stand, on a
 * public holiday, or past a minor's daily or continuous cap — and a minor left
 * without an adult beside them.
 *
 * <p>These are the rules {@link EligibleAnimateurMoveFilter} keeps out of the
 * search, and the filter is not a wall: the recreate step of the ruin and
 * recreate move runs its own construction heuristic, which Timefold 2.5 does
 * not let us filter. At one hard point per seat, those rules weighed exactly
 * what an empty seat or an unkept ad hoc exception weighs, and less than a
 * few minutes of missing rest — so on a plan that could not hold everything
 * the solver placed a fifteen-year-old at night rather than leave a seat
 * empty, or an animateur on the day they had declared off rather than leave a
 * forced assignment unkept. Both were measured on the ladder's infeasible
 * rungs.</p>
 *
 * <p>A minor without an adult is not a filter motif — it depends on who else
 * holds the stand — but it is the same kind of rule, and it had the same flaw:
 * on a team of minors only, leaving a minor alone cost exactly what leaving the
 * seat empty cost, and nothing but the search order chose between them.</p>
 *
 * <p>Each breach therefore costs a flat {@link #FORFAIT} more than anything a
 * single seat can change elsewhere. The rules penalised in minutes stay under
 * it: one seat moves a weekly rest deficit by at most its 35 hours (2 100
 * minutes), a daily rest, a daily or weekly cap, a meal break or a gap between
 * vacations by less — together still under half the flat cost. The flat cost
 * lives in the match weigher, not in the constraint weight, so an edition that
 * doses one of these rules multiplies it instead of replacing it.</p>
 */
public final class ExclusionEligibilite {

    /** The flat hard cost of one breach, on top of the minutes a capped rule counts. */
    public static final int FORFAIT = 10_000;

    /** The constraint no filter motif names but that carries the flat cost too. */
    public static final String ENCADREMENT_DES_MINEURS = "mineurNecessiteEncadrementMajeur";

    /** The constraints that carry it: the ones the eligibility filter names, and the supervision of minors. */
    public static final Set<String> CONTRAINTES = Stream.concat(
                    Arrays.stream(EligibleAnimateurMoveFilter.Motif.values())
                            .map(EligibleAnimateurMoveFilter.Motif::contrainte),
                    Stream.of(ENCADREMENT_DES_MINEURS))
            .collect(Collectors.toUnmodifiableSet());

    private ExclusionEligibilite() {}
}
