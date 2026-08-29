package dev.sylvain.planning.service.diagnostic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ai.timefold.solver.core.api.score.HardMediumSoftScore;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;

/**
 * What the constraints say about handing one seat to one animateur, without
 * changing anything: the score the plan would then have, and which rules that
 * animateur would newly strain.
 *
 * <p>This record is the whole point of {@link ConstraintDiagnosticService#hypotheses}:
 * a screen that wants to tell a user « il ne peut pas, il serait à 11 h ce
 * jour-là » gets the answer <b>from the constraints themselves</b> rather than
 * from a second implementation of the same rule. Nothing here knows what a
 * daily cap is.</p>
 *
 * <p><b>Everything here is measured against the seat being empty</b>, never
 * against its current occupant — see {@link ConstraintDiagnosticService#hypotheses}
 * for why that distinction is the difference between an honest answer and a
 * flattering one.</p>
 *
 * @param animateurId          the candidate
 * @param scoreApres           the plan's score with the candidate on the seat.
 *                             An absolute score, so a caller wanting the cost
 *                             relative to the current occupant only has to
 *                             probe that occupant too and subtract
 * @param delta                {@code scoreApres} minus the score of the plan
 *                             with the seat <b>empty</b>
 * @param contraintesAggravees names of the constraints whose own contribution
 *                             got strictly worse than with the seat empty —
 *                             the reasons, each one a key of
 *                             {@code ConstraintCatalog}
 */
public record AffectationHypothesis(String animateurId, HardMediumSoftScore scoreApres,
        HardMediumSoftScore delta, List<String> contraintesAggravees) {

    public AffectationHypothesis {
        contraintesAggravees = List.copyOf(contraintesAggravees);
    }

    /** True when this candidate breaks a hard rule the empty seat did not — see {@link #delta()}. */
    public boolean degradesHardScore() {
        return delta.hardScore() < 0;
    }

    /**
     * The obvious, slow way: one full {@link ConstraintDiagnosticService#analyze}
     * per candidate, exactly as {@code PlanningService.simulateSwap} does for
     * the single candidate it is handed.
     *
     * <p>It is the reference implementation on purpose. Any faster path — and
     * {@link ScoreDirectorConstraintDiagnosticService} has one — has to agree
     * with it candidate for candidate, which
     * {@code AffectationHypothesisTest} checks; being able to run the reference
     * on the very same Community build is what makes that check meaningful,
     * unlike the Enterprise-gated oracle of
     * {@code ConstraintDiagnosticServiceContractTest}.</p>
     */
    static List<AffectationHypothesis> byFullAnalysis(ConstraintDiagnosticService diagnostic,
            PlanningEvenement solution, PosteAffectation cible, List<Animateur> candidats) {
        Animateur initial = cible.getAnimateur();
        List<AffectationHypothesis> hypotheses = new ArrayList<>(candidats.size());
        try {
            cible.setAnimateur(null);
            PlanningAnalysis avant = diagnostic.analyze(solution);
            Map<String, HardMediumSoftScore> totalsBefore = totals(avant);
            for (Animateur candidat : candidats) {
                cible.setAnimateur(candidat);
                PlanningAnalysis apres = diagnostic.analyze(solution);
                hypotheses.add(new AffectationHypothesis(candidat.getId(), apres.score(),
                        apres.score().subtract(avant.score()),
                        worsened(totalsBefore, totals(apres))));
            }
        } finally {
            cible.setAnimateur(initial);
            // The score the caller's solution carries is a side effect of
            // analyze(), so the last candidate's would otherwise stay on it.
            diagnostic.analyze(solution);
        }
        return List.copyOf(hypotheses);
    }

    private static Map<String, HardMediumSoftScore> totals(PlanningAnalysis analysis) {
        Map<String, HardMediumSoftScore> totals = new HashMap<>();
        for (ConstraintContribution contribution : analysis.contributions()) {
            totals.put(contribution.constraintName(), contribution.score());
        }
        return totals;
    }

    /**
     * Constraints whose contribution got strictly worse, sorted by name.
     *
     * <p>Sorted rather than left in encounter order because the two
     * implementations walk the constraint session in their own order, and this
     * list is what a test compares them on: an incidental ordering difference
     * would read as a disagreement about the rules.</p>
     *
     * <p>A constraint absent from the « avant » map matched nothing and
     * contributed zero, so a first match makes it worse just the same.</p>
     */
    static List<String> worsened(Map<String, HardMediumSoftScore> avant, Map<String, HardMediumSoftScore> apres) {
        List<String> worse = new ArrayList<>(1);
        for (Map.Entry<String, HardMediumSoftScore> entry : apres.entrySet()) {
            HardMediumSoftScore precedent = avant.getOrDefault(entry.getKey(), HardMediumSoftScore.ZERO);
            if (entry.getValue().compareTo(precedent) < 0) {
                worse.add(entry.getKey());
            }
        }
        worse.sort(null);
        return worse;
    }
}
