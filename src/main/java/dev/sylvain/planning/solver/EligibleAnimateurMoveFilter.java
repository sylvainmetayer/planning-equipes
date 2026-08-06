package dev.sylvain.planning.solver;

import java.time.LocalTime;

import ai.timefold.solver.core.api.score.director.ScoreDirector;
import ai.timefold.solver.core.impl.heuristic.selector.common.decorator.SelectionFilter;
import ai.timefold.solver.core.impl.heuristic.selector.move.generic.ChangeMove;
import ai.timefold.solver.core.impl.heuristic.selector.move.generic.SwapMove;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PlanningFestival;
import dev.sylvain.planning.domain.PosteAffectation;

/**
 * The {@code animateurRange} value range spans every animateur (~150), because
 * eligibility depends on the target poste (stand competence, date
 * availability), not on a static property of the animateur. Without this
 * filter, the construction heuristic and local search spend most of their
 * moves on obviously-hard-invalid assignments (wrong competence, declared
 * unavailable) and pay for a full incremental score calculation to find that
 * out. Rejecting them here, before scoring, is what keeps solving fast enough
 * on constrained hardware (e.g. a Raspberry Pi) for the full ~150
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
     * <p>Mirrors, in order: {@code competenceCompatible},
     * {@code animateurDisponible}, {@code standReserveAuxMajeurs},
     * {@code travailDeNuitInterditPourMineur}, {@code dureeQuotidienneMaxMineur}
     * and {@code travailContinuMaxMineur} (the last two only in their
     * single-créneau form). Keep this list and {@code LegalConstraints} in
     * sync: a filter stricter than the constraints would hide feasible
     * solutions.</p>
     */
    private static boolean estEligible(PosteAffectation poste, Animateur animateur) {
        if (animateur == null) {
            return true;
        }
        Creneau creneau = poste.getCreneau();
        if (!animateur.possedeCompetencePour(poste.getStand())
                || animateur.estIndisponibleLe(creneau.getDate())) {
            return false;
        }
        if (!animateur.estMineurLe(creneau.getDate())) {
            return true;
        }
        boolean moinsDe16Ans = animateur.estMoinsDe16AnsLe(creneau.getDate());
        LocalTime debutNuit = moinsDe16Ans
                ? Creneau.DEBUT_NUIT_MOINS_DE_16_ANS
                : Creneau.DEBUT_NUIT_16_A_18_ANS;
        int plafondQuotidien = moinsDe16Ans ? DUREE_QUOTIDIENNE_MAX_MOINS_DE_16_ANS : DUREE_QUOTIDIENNE_MAX_MINEUR;
        return !poste.getStand().isReserveMajeurs()
                && !creneau.chevaucheNuit(debutNuit)
                && creneau.getDureeMinutes() <= plafondQuotidien
                && creneau.getDureeMinutes() <= TRAVAIL_CONTINU_MAX_MINEUR;
    }

    /** Art. L3162-1, mirrored from {@code LegalConstraints}. */
    private static final int DUREE_QUOTIDIENNE_MAX_MINEUR = 8 * 60;

    /** Art. D4153-3, mirrored from {@code LegalConstraints}. */
    private static final int DUREE_QUOTIDIENNE_MAX_MOINS_DE_16_ANS = 7 * 60;

    /** Art. L3162-3, mirrored from {@code LegalConstraints}. */
    private static final int TRAVAIL_CONTINU_MAX_MINEUR = 4 * 60 + 30;

    public static final class ChangeMoveFilter implements SelectionFilter<PlanningFestival, ChangeMove<PlanningFestival>> {
        @Override
        public boolean accept(ScoreDirector<PlanningFestival> scoreDirector, ChangeMove<PlanningFestival> move) {
            PosteAffectation poste = (PosteAffectation) move.getEntity();
            Animateur animateur = (Animateur) move.getToPlanningValue();
            return estEligible(poste, animateur);
        }
    }

    public static final class SwapMoveFilter implements SelectionFilter<PlanningFestival, SwapMove<PlanningFestival>> {
        @Override
        public boolean accept(ScoreDirector<PlanningFestival> scoreDirector, SwapMove<PlanningFestival> move) {
            PosteAffectation left = (PosteAffectation) move.getLeftEntity();
            PosteAffectation right = (PosteAffectation) move.getRightEntity();
            return estEligible(left, right.getAnimateur()) && estEligible(right, left.getAnimateur());
        }
    }
}
