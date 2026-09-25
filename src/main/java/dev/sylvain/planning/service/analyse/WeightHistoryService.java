package dev.sylvain.planning.service.analyse;

import dev.sylvain.planning.service.EditionContext;
import dev.sylvain.planning.service.analyse.KpiHistoriqueService.KpiHistoriqueEntry;
import dev.sylvain.planning.service.analyse.PlanningKpiService.PlanningKpi;
import dev.sylvain.planning.service.referentiel.WeightChange;
import dev.sylvain.planning.service.referentiel.WeightHistoryRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * The weight history of an edition set beside the solves that followed it:
 * juxtaposition, not causality. The referential may have moved between two
 * solves as much as the weights did, and nothing here claims to isolate the
 * effect of a weight — the screen says so.
 */
@ApplicationScoped
public class WeightHistoryService {

    private final WeightHistoryRepository history;

    private final KpiHistoriqueService kpiHistory;

    private final EditionContext editionContext;

    @Inject
    public WeightHistoryService(
            WeightHistoryRepository history, KpiHistoriqueService kpiHistory, EditionContext editionContext) {
        this.history = history;
        this.kpiHistory = kpiHistory;
        this.editionContext = editionContext;
    }

    /**
     * One finished solve of the edition, reduced to what a weighting is read
     * against.
     *
     * @param ruleViolations how many times the rule asked about was broken —
     *                       {@code null} on the global history, and when the
     *                       solve measured no violation of it
     * @param dosage         the weighting it ran under, {@code null} on a line
     *                       older than the figure (« dosage inconnu »)
     */
    @Schema(requiredProperties = {"id", "createdAt"})
    public record ResolutionUnderDosage(
            long id,
            Instant createdAt,
            String score,
            Integer scoreHard,
            Integer scoreMedium,
            Integer scoreSoft,
            Integer scoreMediumHorsPlancher,
            Integer ruleViolations,
            Dosage dosage) {}

    /**
     * The history of one rule, or of all of them when {@code name} is
     * {@code null}: the changes and the solves, each oldest first.
     */
    @Schema(requiredProperties = {"changes", "resolutions"})
    public record ConstraintHistory(String name, List<WeightChange> changes, List<ResolutionUnderDosage> resolutions) {}

    /**
     * {@code name}'s changes and the edition's solves. A rule that left the
     * catalogue is answered all the same, from its name: its history stays
     * readable.
     */
    public ConstraintHistory forConstraint(String name) {
        return new ConstraintHistory(name, history.list(name), resolutions(name));
    }

    /** Every rule's changes and the edition's solves. */
    public ConstraintHistory all() {
        return new ConstraintHistory(null, history.list(null), resolutions(null));
    }

    private List<ResolutionUnderDosage> resolutions(String name) {
        String editionId = editionContext.editionIdCourant();
        return kpiHistory.list().stream()
                .filter(entry -> editionId.equals(entry.editionId()) && entry.kpi() != null)
                .sorted(Comparator.comparing(
                                KpiHistoriqueEntry::creeLe, Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparingLong(KpiHistoriqueEntry::id))
                .map(entry -> resolution(entry, name))
                .toList();
    }

    private static ResolutionUnderDosage resolution(KpiHistoriqueEntry entry, String name) {
        PlanningKpi kpi = entry.kpi();
        Integer violations = name == null || kpi.violationsParContrainte() == null
                ? null
                : kpi.violationsParContrainte().get(name);
        return new ResolutionUnderDosage(
                entry.id(),
                entry.creeLe(),
                kpi.score(),
                kpi.scoreHard(),
                kpi.scoreMedium(),
                kpi.scoreSoft(),
                kpi.scoreMediumHorsPlancher(),
                violations,
                kpi.dosage());
    }
}
