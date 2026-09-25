package dev.sylvain.planning.service.solve;

import dev.sylvain.planning.domain.Edition;
import dev.sylvain.planning.service.EditionContext;
import dev.sylvain.planning.service.analyse.PlanningKpiService;
import dev.sylvain.planning.service.analyse.PlanningKpiService.PlanningKpi;
import dev.sylvain.planning.service.edition.EditionRepository;
import dev.sylvain.planning.service.solve.PlanSnapshotService.SnapshotDetail;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * A/B comparator (issue #70): confronts a baseline with a variant — two plan
 * snapshots, or a snapshot and the plan currently persisted. It is a pure
 * <b>read</b> of metrics already measured: it never launches a solve and never
 * recomputes a score.
 *
 * <p>Both sides may belong to <b>different editions</b>. Since #172 the
 * edition is the variant carrier (switching variant means duplicating the
 * edition), so "baseline versus variant" is most often a cross-edition
 * question; the comparison then reports {@code editionsDifferentes} so the UI
 * can say out loud that two different referentials are being confronted.</p>
 *
 * <p>Everything compared is <b>non-nominative</b>, like the KPI it reads:
 * fairness is a dispersion of hours, never a ranking of named animateurs.</p>
 */
@ApplicationScoped
public class SnapshotComparisonService {

    /** Value designating the currently persisted plan instead of a snapshot id. */
    public static final String COURANT = "courant";

    @Inject
    PlanSnapshotService snapshotService;

    @Inject
    PlanningKpiService kpiService;

    @Inject
    PlanningPersistenceService persistenceService;

    @Inject
    EditionContext editionContext;

    @Inject
    dev.sylvain.planning.service.consigne.ConsigneRepository consigneRepository;

    @Inject
    EditionRepository editionRepository;

    /**
     * One side of the comparison.
     *
     * @param snapshotId    {@code null} when this side is the currently
     *                      persisted plan rather than a snapshot
     * @param libelle       the snapshot's label, {@code null} for the current
     *                      plan — naming that side is the UI's job, in the
     *                      user's language
     * @param kpiRecalcule  the KPI were not stored with the snapshot and had to
     *                      be recomputed in degraded mode: coverage and
     *                      volumetry are exact, violations are not measured
     */
    @Schema(requiredProperties = {"kpiRecalcule"})
    public record CoteComparaison(
            Long snapshotId,
            String libelle,
            String editionId,
            String editionNom,
            Instant creeLe,
            PlanningKpi kpi,
            boolean kpiRecalcule,
            List<PlanSnapshotService.ConsigneSnapshot> consignes) {}

    /**
     * Violation counts of one constraint on each side. {@code null} on a side
     * that never measured it — absence and zero are not the same fact, and
     * only one of the two is good news.
     */
    public record DiffContrainte(String contrainte, Integer base, Integer variante) {}

    /**
     * @param editionsDifferentes    the two sides belong to different editions,
     *                               hence to different referentials: the UI must
     *                               warn that this is not an all-else-equal
     *                               comparison
     * @param volumetriesDifferentes the two plans do not hold the same number of
     *                               seats, so absolute scores are not directly
     *                               comparable whatever the editions
     * @param consignesDifferentes the two sides were not captured under the
     *                             same consignes (issue #4): a difference in
     *                             seats or hours then comes from a band an
     *                             arrêté closed, not from a solver setting.
     *                             False when either side predates the figure,
     *                             since nothing can be said
     */
    @Schema(requiredProperties = {"editionsDifferentes", "volumetriesDifferentes", "consignesDifferentes"})
    public record ComparaisonSnapshots(
            CoteComparaison base,
            CoteComparaison variante,
            boolean editionsDifferentes,
            boolean volumetriesDifferentes,
            List<DiffContrainte> diffViolations,
            boolean consignesDifferentes) {}

    /**
     * Compares two sides, each designated either by a snapshot id or by
     * {@link #COURANT}. Returns {@code null} when a designated snapshot does
     * not exist — in any edition, see
     * {@link PlanSnapshotService#loadAllEditions(long)}.
     */
    public ComparaisonSnapshots comparer(String base, String variante) {
        CoteComparaison coteBase = cote(base);
        CoteComparaison coteVariante = cote(variante);
        if (coteBase == null || coteVariante == null) {
            return null;
        }
        return new ComparaisonSnapshots(
                coteBase,
                coteVariante,
                !Objects.equals(coteBase.editionId(), coteVariante.editionId()),
                coteBase.kpi().postesTotal() != coteVariante.kpi().postesTotal(),
                diffViolations(coteBase.kpi(), coteVariante.kpi()),
                coteBase.consignes() != null
                        && coteVariante.consignes() != null
                        && !Objects.equals(coteBase.consignes(), coteVariante.consignes()));
    }

    /** {@code null} when {@code selecteur} designates a snapshot that does not exist. */
    private CoteComparaison cote(String selecteur) {
        if (selecteur == null || selecteur.isBlank()) {
            return null;
        }
        if (COURANT.equalsIgnoreCase(selecteur.trim())) {
            return cotePlanCourant();
        }
        long id;
        try {
            id = Long.parseLong(selecteur.trim());
        } catch (NumberFormatException _) {
            return null;
        }
        SnapshotDetail detail = snapshotService.loadAllEditions(id);
        return detail == null ? null : cote(detail);
    }

    private CoteComparaison cotePlanCourant() {
        String editionId = editionContext.editionIdCourant();
        PlanningPersistenceService.PlanningResolution resolution = persistenceService.loadResolution();
        return new CoteComparaison(
                null,
                null,
                editionId,
                nomEdition(editionId),
                resolution == null ? null : resolution.resoluLe(),
                kpiService.computeCurrent(null),
                false,
                consigneRepository.list().stream()
                        .map(consigne -> new PlanSnapshotService.ConsigneSnapshot(
                                consigne.date(), consigne.fermetureDebut(), consigne.fermetureFin(), consigne.motif()))
                        .toList());
    }

    private CoteComparaison cote(SnapshotDetail detail) {
        PlanningKpi kpi = detail.meta().kpi();
        boolean recalcule = kpi == null;
        if (recalcule) {
            // In the snapshot's own edition: the degraded recomputation resolves
            // the seats' hours against the créneaux they were captured on, and
            // those live in that edition — not in the one the browser happens
            // to be looking at.
            kpi = editionContext.executeIn(
                    detail.meta().editionId(),
                    () -> kpiService.computeFromSnapshot(
                            detail.affectations(), detail.meta().score()));
        }
        return new CoteComparaison(
                detail.meta().id(),
                detail.meta().libelle(),
                detail.meta().editionId(),
                detail.meta().editionNom(),
                detail.meta().creeLe(),
                kpi,
                recalcule,
                detail.meta().consignes());
    }

    private String nomEdition(String editionId) {
        return editionRepository.listEditions().stream()
                .filter(edition -> edition.getId().equals(editionId))
                .map(Edition::getNom)
                .findFirst()
                .orElse(editionId);
    }

    /**
     * Union of both sides' constraints, base first then the ones only the
     * variant knows. Static so the absent-on-one-side semantics can be
     * unit-tested without a database.
     */
    static List<DiffContrainte> diffViolations(PlanningKpi base, PlanningKpi variante) {
        // Null-guarded: a KPI payload deserialized from an older row may lack the map.
        Map<String, Integer> violationsBase =
                base.violationsParContrainte() == null ? Map.of() : base.violationsParContrainte();
        Map<String, Integer> violationsVariante =
                variante.violationsParContrainte() == null ? Map.of() : variante.violationsParContrainte();
        Set<String> contraintes = new LinkedHashSet<>();
        contraintes.addAll(violationsBase.keySet());
        contraintes.addAll(violationsVariante.keySet());
        List<DiffContrainte> diff = new ArrayList<>();
        for (String contrainte : contraintes) {
            diff.add(
                    new DiffContrainte(contrainte, violationsBase.get(contrainte), violationsVariante.get(contrainte)));
        }
        return diff;
    }
}
