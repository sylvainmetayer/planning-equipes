package dev.sylvain.planning.api;

import dev.sylvain.planning.service.FeasibilityAnalyzer;
import dev.sylvain.planning.service.FeasibilityAnalyzer.FeasibilityReport;
import dev.sylvain.planning.service.ReferenceDataService;
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

    @Inject
    ReferenceDataService referenceDataService;

    @Inject
    FeasibilityAnalyzer feasibilityAnalyzer;

    /**
     * Créneaux are read from the <em>active</em> group only, like
     * {@code PlanningService} does when it builds a problem: créneaux of the
     * other groups are not part of the next solve, and counting them would
     * report shortfalls on days nobody intends to schedule.
     */
    @GET
    public FeasibilityReport analyser() {
        return feasibilityAnalyzer.analyser(
                referenceDataService.listAnimateurs(),
                referenceDataService.listStands(),
                referenceDataService.listCreneauxGroupeActif());
    }
}
