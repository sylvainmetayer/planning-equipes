package dev.sylvain.planning.service.solve;

import ai.timefold.solver.core.api.score.HardMediumSoftScore;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.service.journee.ChangementsJourneeService;
import java.time.LocalDate;
import java.util.Objects;
import java.util.TreeSet;

/**
 * What {@link RuleBenchTest} reads off two solved plans, pure so that
 * {@code RuleBenchMeasureTest} checks it without solving anything.
 */
final class RuleBenchMeasure {

    /** Share of the baseline medium the decision accepts to lose. */
    static final double MEDIUM_LOSS_THRESHOLD = 0.05;

    private RuleBenchMeasure() {}

    /**
     * The seats whose holder differs between the two plans, on the natural key
     * (stand, day, hours) through the application's own comparison: two seats
     * of one cell are interchangeable, so the poste ids are never compared.
     */
    static int changedSeats(PlanningEvenement before, PlanningEvenement after) {
        TreeSet<LocalDate> days = new TreeSet<>();
        for (PlanningEvenement plan : new PlanningEvenement[] {before, after}) {
            plan.getPostes().stream()
                    .map(PosteAffectation::getCreneau)
                    .filter(Objects::nonNull)
                    .map(Creneau::getDate)
                    .filter(Objects::nonNull)
                    .forEach(days::add);
        }
        return days.stream()
                .mapToInt(day -> ChangementsJourneeService.compareSeats(day, before, after)
                        .size())
                .sum();
    }

    /** The share of the baseline medium that the variant's plan gives up; 0 when it is as good or better. */
    static double mediumLost(HardMediumSoftScore before, HardMediumSoftScore after) {
        long worse = before.mediumScore() - after.mediumScore();
        if (worse <= 0) {
            return 0;
        }
        long base = Math.abs(before.mediumScore());
        // Any loss from a perfect medium is a full loss: there is no share to take.
        return base == 0 ? 1 : (double) worse / base;
    }

    /** A hard score worse than the baseline's always refuses; otherwise the medium lost decides. */
    static String verdict(HardMediumSoftScore before, HardMediumSoftScore after) {
        if (after.hardScore() < before.hardScore()) {
            return before.isFeasible() ? "REFUSE (faisabilite perdue)" : "REFUSE (hard)";
        }
        return mediumLost(before, after) <= MEDIUM_LOSS_THRESHOLD ? "ACCEPTE" : "REFUSE (medium)";
    }
}
