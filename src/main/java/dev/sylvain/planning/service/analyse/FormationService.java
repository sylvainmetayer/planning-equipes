package dev.sylvain.planning.service.analyse;

import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.service.analyse.FormationAnalyzer.PlanFormation;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Objects;

/**
 * The training plan of the current edition — what {@code GET /api/formation}
 * and the MCP tool {@code plan_formation} both return.
 *
 * <p>It gathers the two reports the plan is read from — the staffing need
 * ({@link StaffingService}, the very call behind the Besoin tab) and the
 * fragility findings of the persisted plan ({@link FragiliteAnalyzer}, the
 * report behind the Fragilité tab, untruncated) — and hands them to
 * {@link FormationAnalyzer}. Nothing here solves, simulates or writes.</p>
 */
@ApplicationScoped
public class FormationService {

    @Inject
    StaffingService staffingService;

    @Inject
    FragiliteAnalyzer fragiliteAnalyzer;

    @Inject
    PlanningPersistenceService persistenceService;

    @Inject
    ReferenceDataService referenceDataService;

    /** Never fails on an empty edition: this feeds a read-only tab opened before any solve. */
    public PlanFormation plan() {
        PlanningEvenement planning = persistenceService.loadPersistedPlanning();
        boolean planPersiste = planning != null
                && planning.getPostes() != null
                && !planning.getPostes().isEmpty();
        return FormationAnalyzer.compute(
                staffingService.analyzeEdition(),
                fragiliteAnalyzer.findings(planning),
                planPersiste,
                referenceDataService.listAnimateurs(),
                referenceDataService.listStands(),
                referenceDataService.listTypologies(),
                referenceDataService.listCreneaux().stream()
                        .map(Creneau::getDate)
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList());
    }
}
