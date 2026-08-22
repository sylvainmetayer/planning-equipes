package dev.sylvain.planning.solver;

import java.time.LocalTime;

import ai.timefold.solver.core.api.score.director.ScoreDirector;
import ai.timefold.solver.core.impl.heuristic.selector.common.decorator.SelectionFilter;
import ai.timefold.solver.core.impl.heuristic.selector.move.generic.ChangeMove;
import ai.timefold.solver.core.impl.heuristic.selector.move.generic.SwapMove;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.JoursFeries;
import dev.sylvain.planning.domain.PlafondsLegauxMineurs;
import dev.sylvain.planning.domain.PlanningFestival;
import dev.sylvain.planning.domain.PosteAffectation;

/**
 * The {@code animateurRange} value range spans every animateur (~150), because
 * eligibility depends on the target poste (date availability, legal minor
 * rules), not on a static property of the animateur. Without this filter, the
 * construction heuristic and local search spend most of their moves on
 * obviously-hard-invalid assignments (declared unavailable, under-age on a
 * night slot...) and pay for a full incremental score calculation to find
 * that out. Rejecting them here, before scoring, is what keeps solving fast
 * enough on constrained hardware (e.g. a Raspberry Pi) for the full ~150
 * animateurs / 2000+ postes scenario.
 */
public final class EligibleAnimateurMoveFilter {

    private EligibleAnimateurMoveFilter() {
    }

    /**
     * Only the exclusions decidable from the (poste, animateur) pair alone —
     * i.e. hard constraints this single assignment violates <b>whatever the
     * rest of the plan looks like</b>. Filtering those out can therefore never
     * discard a hard-feasible solution: leaving the seat empty costs exactly
     * one hard point ({@code posteDoitEtrePourvu}), and so would the rejected
     * assignment.
     *
     * <p>Deliberately excludes everything that depends on the animateur's other
     * assignments (daily/weekly caps, rest, breaks, adult supervision): those
     * are the score's job, not the filter's.</p>
     *
     * <p>Mirrors, in order: {@code animateurDisponible},
     * {@code standReserveAuxMajeurs}, {@code travailInterditJourFerieMineur},
     * {@code travailDeNuitInterditPourMineur}, {@code dureeQuotidienneMaxMineur}
     * and {@code travailContinuMaxMineur} (the last two only in their
     * single-créneau form). A filter stricter than the constraints would hide
     * feasible solutions, so the caps both sides check come from the single
     * {@link PlafondsLegauxMineurs} declaration rather than from a copy kept
     * in sync by hand; keep the <i>list of mirrored rules</i> above in step
     * with {@code LegalConstraints} the same way.</p>
     *
     * <p>Competence is deliberately <b>not</b> excluded here: the business now
     * treats it as an administrator's appreciation, enforced only as a medium
     * constraint ({@code QualiteConstraints.appreciationIncompatible}), so an
     * animateur without a matching appreciation is a valid — just penalised —
     * assignment, one this filter must let through.</p>
     */
    public static boolean isEligible(PosteAffectation poste, Animateur animateur) {
        if (animateur == null) {
            return true;
        }
        Creneau creneau = poste.getCreneau();
        if (animateur.isIndisponibleOn(creneau.getDate())) {
            return false;
        }
        if (!animateur.isMineurOn(creneau.getDate())) {
            return true;
        }
        boolean moinsDe16Ans = animateur.isUnder16On(creneau.getDate());
        LocalTime debutNuit = moinsDe16Ans
                ? Creneau.DEBUT_NUIT_MOINS_DE_16_ANS
                : Creneau.DEBUT_NUIT_16_A_18_ANS;
        int dailyCap = PlafondsLegauxMineurs.dureeQuotidienneMaxMinutes(moinsDe16Ans);
        return !poste.getStand().isReserveMajeurs()
                && !JoursFeries.isFerieInFrance(creneau.getDate())
                && !creneau.chevaucheNuit(debutNuit)
                && creneau.getDureeMinutes() <= dailyCap
                && creneau.getDureeMinutes() <= PlafondsLegauxMineurs.TRAVAIL_CONTINU_MAX_MINUTES;
    }

    public static final class ChangeMoveFilter implements SelectionFilter<PlanningFestival, ChangeMove<PlanningFestival>> {
        @Override
        public boolean accept(ScoreDirector<PlanningFestival> scoreDirector, ChangeMove<PlanningFestival> move) {
            PosteAffectation poste = (PosteAffectation) move.getEntity();
            Animateur animateur = (Animateur) move.getToPlanningValue();
            return isEligible(poste, animateur);
        }
    }

    public static final class SwapMoveFilter implements SelectionFilter<PlanningFestival, SwapMove<PlanningFestival>> {
        @Override
        public boolean accept(ScoreDirector<PlanningFestival> scoreDirector, SwapMove<PlanningFestival> move) {
            PosteAffectation left = (PosteAffectation) move.getLeftEntity();
            PosteAffectation right = (PosteAffectation) move.getRightEntity();
            return isEligible(left, right.getAnimateur()) && isEligible(right, left.getAnimateur());
        }
    }
}
