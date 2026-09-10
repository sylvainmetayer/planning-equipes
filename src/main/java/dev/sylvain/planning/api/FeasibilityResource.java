package dev.sylvain.planning.api;

import dev.sylvain.planning.service.analyse.FeasibilityAnalyzer;
import dev.sylvain.planning.service.analyse.FeasibilityAnalyzer.FeasibilityReport;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
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
     * report shortfalls on days nobody intends to schedule. Stands come
     * resolved, for the same reason: the analysis decides which stands are open
     * on a créneau, so it has to see what the recurring horaires expand to.
     * The ad hoc constraints come along so contradictory exceptions — refused
     * at entry time, but possibly recorded before that check existed or
     * imported together — are reported here too (issue #84).
     */
    @GET
    public FeasibilityReport analyze() {
        return feasibilityAnalyzer.analyze(
                referenceDataService.listAnimateurs(),
                referenceDataService.listSolvedStands(),
                referenceDataService.listCreneaux(),
                referenceDataService.listContraintesAdHoc());
    }
}
