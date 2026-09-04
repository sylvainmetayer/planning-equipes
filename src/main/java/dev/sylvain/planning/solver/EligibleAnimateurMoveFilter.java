package dev.sylvain.planning.solver;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import ai.timefold.solver.core.impl.heuristic.selector.common.decorator.SelectionFilter;
import ai.timefold.solver.core.impl.heuristic.selector.move.generic.SelectorBasedChangeMove;
import ai.timefold.solver.core.impl.heuristic.selector.move.generic.SelectorBasedSwapMove;
import ai.timefold.solver.core.impl.score.director.ScoreDirector;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.JoursFeries;
import dev.sylvain.planning.domain.PlafondsLegauxMineurs;
import dev.sylvain.planning.domain.PlanningEvenement;
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
     * One reason a (poste, animateur) pair is refused before the score ever
     * sees it, and the hard constraint of {@code AffectationConstraints} /
     * {@code LegalConstraints} it mirrors.
     *
     * <p>The constraint name is carried, not restated in words: it is the key
     * {@code ConstraintCatalog} already indexes, so the wording a screen shows
     * for a refusal is the wording the Contraintes screen shows for the rule
     * itself, and adding a reason here without a rule behind it would be
     * caught by {@code EligibleAnimateurMoveFilterTest}.</p>
     */
    public enum Motif {

        /** {@code joursIndisponibles} covers the créneau's date. */
        INDISPONIBLE("animateurDisponible"),
        /** A minor on a stand flagged {@code reserveMajeurs}. */
        STAND_RESERVE_AUX_MAJEURS("standReserveAuxMajeurs"),
        /** A minor on a public holiday. */
        JOUR_FERIE_MINEUR("travailInterditJourFerieMineur"),
        /** The créneau overlaps this minor's legal night window. */
        TRAVAIL_DE_NUIT_MINEUR("travailDeNuitInterditPourMineur"),
        /** This single créneau already exceeds the minor's daily cap. */
        DUREE_QUOTIDIENNE_MINEUR("dureeQuotidienneMaxMineur"),
        /** This single créneau already exceeds the minor's uninterrupted-work cap. */
        TRAVAIL_CONTINU_MINEUR("travailContinuMaxMineur");

        static final Motif[] VALUES = values();

        private final String contrainte;

        Motif(String contrainte) {
            this.contrainte = contrainte;
        }

        /** Name of the hard constraint this reason mirrors, as passed to {@code asConstraint(...)}. */
        public String contrainte() {
            return contrainte;
        }

        int bit() {
            return 1 << ordinal();
        }
    }

    /**
     * Why this animateur may not take this poste, as far as the (poste,
     * animateur) pair alone can tell — i.e. the hard constraints this single
     * assignment violates <b>whatever the rest of the plan looks like</b>.
     * Empty means eligible.
     *
     * <p>Kept as a bit mask rather than a collection because
     * {@link #isEligible} is on the solver's hottest path: the move filters
     * ask it millions of times per solve, and a list allocated per call would
     * be paid there for the sole benefit of a read-only screen. Decoding the
     * mask into {@link Motif}s ({@link #motifs}) is what the screen does, once
     * per animateur.</p>
     *
     * <p>Every exclusion is evaluated — none short-circuits once one has
     * matched — because the caller that asks for reasons needs all of them:
     * knowing that lifting « mineur » still leaves « plafond » behind is the
     * difference between a fixable and an unfixable seat.</p>
     */
    static int motifsMask(PosteAffectation poste, Animateur animateur, boolean pauseSurPoste) {
        if (animateur == null) {
            return 0;
        }
        Creneau creneau = poste.getCreneau();
        int mask = animateur.isIndisponibleOn(creneau.getDate()) ? Motif.INDISPONIBLE.bit() : 0;
        if (!animateur.isMineurOn(creneau.getDate())) {
            return mask;
        }
        boolean moinsDe16Ans = animateur.isUnder16On(creneau.getDate());
        LocalTime debutNuit = moinsDe16Ans
                ? Creneau.DEBUT_NUIT_MOINS_DE_16_ANS
                : Creneau.DEBUT_NUIT_16_A_18_ANS;
        int dailyCap = PlafondsLegauxMineurs.dureeQuotidienneMaxMinutes(moinsDe16Ans);
        if (poste.getStand().isReserveMajeurs()) {
            mask |= Motif.STAND_RESERVE_AUX_MAJEURS.bit();
        }
        if (JoursFeries.isFerieInFrance(creneau.getDate())) {
            mask |= Motif.JOUR_FERIE_MINEUR.bit();
        }
        if (creneau.chevaucheNuit(debutNuit)) {
            mask |= Motif.TRAVAIL_DE_NUIT_MINEUR.bit();
        }
        int duree = creneau.getDureeMinutes();
        // Breaks declared as taken on the post are rest, not work: the same
        // deduction dureeQuotidienneMaxMineur applies, on this single créneau.
        int effectif = pauseSurPoste ? duree - PlafondsLegauxMineurs.onPostBreakMinutes(duree) : duree;
        if (effectif > dailyCap) {
            mask |= Motif.DUREE_QUOTIDIENNE_MINEUR.bit();
        }
        if (!pauseSurPoste && duree > PlafondsLegauxMineurs.TRAVAIL_CONTINU_MAX_MINUTES) {
            mask |= Motif.TRAVAIL_CONTINU_MINEUR.bit();
        }
        return mask;
    }

    /**
     * The same verdict as {@link #isEligible}, spelled out: every reason this
     * pair is refused, in declaration order, each naming the constraint it
     * mirrors. Empty for an eligible pair.
     *
     * <p>This is what the « banc de touche » screen reads, so that a reason
     * shown to a user and a candidate refused by the solver can never be two
     * different judgements.</p>
     */
    public static List<Motif> motifs(PosteAffectation poste, Animateur animateur, boolean pauseSurPoste) {
        int mask = motifsMask(poste, animateur, pauseSurPoste);
        if (mask == 0) {
            return List.of();
        }
        List<Motif> motifs = new ArrayList<>(2);
        for (Motif motif : Motif.VALUES) {
            if ((mask & motif.bit()) != 0) {
                motifs.add(motif);
            }
        }
        return List.copyOf(motifs);
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
     * single-créneau form, and read with the organiser's
     * {@code pauseSurPoste} declaration exactly as the constraints do: a break
     * taken on the post is deducted from the day, and no longer caps the
     * stretch) — the list {@link Motif} now holds, each entry
     * naming its constraint. A filter stricter than the constraints would hide
     * feasible solutions, so the caps both sides check come from the single
     * {@link PlafondsLegauxMineurs} declaration rather than from a copy kept
     * in sync by hand; keep {@link Motif} in step with {@code LegalConstraints}
     * the same way.</p>
     *
     * <p>Competence is deliberately <b>not</b> excluded here: the business now
     * treats it as an administrator's appreciation, enforced only as a medium
     * constraint ({@code QualiteConstraints.appreciationIncompatible}), so an
     * animateur without a matching appreciation is a valid — just penalised —
     * assignment, one this filter must let through.</p>
     */
    public static boolean isEligible(PosteAffectation poste, Animateur animateur, boolean pauseSurPoste) {
        return motifsMask(poste, animateur, pauseSurPoste) == 0;
    }

    public static final class ChangeMoveFilter implements SelectionFilter<PlanningEvenement, SelectorBasedChangeMove<PlanningEvenement>> {
        @Override
        public boolean accept(ScoreDirector<PlanningEvenement> scoreDirector, SelectorBasedChangeMove<PlanningEvenement> move) {
            PosteAffectation poste = (PosteAffectation) move.getEntity();
            Animateur animateur = (Animateur) move.getToPlanningValue();
            return isEligible(poste, animateur, scoreDirector.getWorkingSolution().pauseSurPosteActive());
        }
    }

    public static final class SwapMoveFilter implements SelectionFilter<PlanningEvenement, SelectorBasedSwapMove<PlanningEvenement>> {
        @Override
        public boolean accept(ScoreDirector<PlanningEvenement> scoreDirector, SelectorBasedSwapMove<PlanningEvenement> move) {
            PosteAffectation left = (PosteAffectation) move.getLeftEntity();
            PosteAffectation right = (PosteAffectation) move.getRightEntity();
            boolean pauseSurPoste = scoreDirector.getWorkingSolution().pauseSurPosteActive();
            return isEligible(left, right.getAnimateur(), pauseSurPoste)
                    && isEligible(right, left.getAnimateur(), pauseSurPoste);
        }
    }
}
