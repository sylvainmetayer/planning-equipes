package dev.sylvain.planning.api;

import dev.sylvain.planning.service.analyse.FeasibilityAnalyzer;
import dev.sylvain.planning.service.analyse.FeasibilityAnalyzer.FeasibilityReport;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import dev.sylvain.planning.service.solve.PlanningService;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Pre-solve feasibility diagnostic: runs the plain-Java capacity check of
 * {@link FeasibilityAnalyzer} on the reference data currently persisted, with
 * no solve involved. The setup screen can therefore warn about structurally
 * blocking causes (a stand nobody is competent for, a créneau short of
 * animateurs) before anyone waits minutes for a solver run.
 *
 * <p>The same report is also exposed, post-solve, as the {@code faisabilite}
 * field of {@code GET /api/constraints} — that one reflects the data of the
 * last analysed solve, this one always reflects the current referential.
 */
@Path("/feasibility")
@Produces(MediaType.APPLICATION_JSON)
public class FeasibilityResource {

    private final ReferenceDataService referenceDataService;

    private final FeasibilityAnalyzer feasibilityAnalyzer;

    private final PlanningPersistenceService persistence;

    /**
     * For its {@code pastHorizon()} alone: the moment the frozen past is judged
     * against (ADR 0044), which only the solver façade pairs with the
     * {@code planning.solver.passe-fige} kill-switch. No solve is started here.
     */
    private final PlanningService planningService;

    @Inject
    public FeasibilityResource(
            ReferenceDataService referenceDataService,
            FeasibilityAnalyzer feasibilityAnalyzer,
            PlanningPersistenceService persistence,
            PlanningService planningService) {
        this.referenceDataService = referenceDataService;
        this.feasibilityAnalyzer = feasibilityAnalyzer;
        this.persistence = persistence;
        this.planningService = planningService;
    }

    /**
     * Créneaux are read from the <em>active</em> group only, like
     * {@code PlanningService} does when it builds a problem: créneaux of the
     * other groups are not part of the next solve, and counting them would
     * report shortfalls on days nobody intends to schedule. Stands come
     * resolved, for the same reason: the analysis decides which stands are open
     * on a créneau, so it has to see what the recurring horaires expand to.
     * The ad hoc constraints come along so contradictory exceptions — refused
     * at entry time, but possibly recorded before that check existed or
     * imported together — are reported here too (issue #84). The locks come
     * along for the same kind of deadlock: a forced assignment naming only
     * people whose schedule is frozen over its whole scope. The seats of the
     * persisted plan are read only when a lock exists —
     * {@link FeasibilityAnalyzer.PlanContext} carries them as a supplier, and
     * the horizon of the frozen past (ADR 0044) alongside them, so a forced
     * assignment left on a day already worked is not reported as blocking.
     */
    @GET
    public FeasibilityReport analyze() {
        return feasibilityAnalyzer.analyze(
                referenceDataService.listAnimateurs(),
                referenceDataService.listSolvedStands(),
                referenceDataService.listCreneaux(),
                referenceDataService.listContraintesAdHoc(),
                FeasibilityAnalyzer.encadrementMineursActif(referenceDataService.getContraintesDesactivees()),
                new FeasibilityAnalyzer.PlanContext(
                        referenceDataService.listVerrouillages(),
                        persistence::loadPlacesTenues,
                        planningService.pastHorizon()));
    }
}
