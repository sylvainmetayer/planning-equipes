package dev.sylvain.planning.api;

import dev.sylvain.planning.service.OuvertureStandsAnalyzer;
import dev.sylvain.planning.service.OuvertureStandsAnalyzer.RapportOuvertures;
import dev.sylvain.planning.service.ReferenceDataService;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Stand × jour view of the opening schedule actually in force, for the
 * "Ouvertures des stands" screen: what a solve would see once recurring
 * horaires are expanded, dated exceptions applied and every window clamped to
 * its créneaux — plus the anomalies worth a second look.
 *
 * <p>Read-only, and no solve involved: it exists so an administrator can
 * validate an opening schedule <em>before</em> spending minutes on a solver
 * run, the same way {@link FeasibilityResource} does for staffing capacity.</p>
 */
@Path("/ouvertures-stands")
@Produces(MediaType.APPLICATION_JSON)
public class OuvertureStandsResource {

    @Inject
    ReferenceDataService referenceDataService;

    /**
     * Stands come <b>resolved</b> and créneaux from the <em>active</em> group
     * only — the exact pair {@code PlanningService} builds a problem from, which
     * is the whole point: the screen must show what the solver gets, not a
     * second interpretation of the same data.
     */
    @GET
    public RapportOuvertures analyze() {
        return OuvertureStandsAnalyzer.analyze(
                referenceDataService.listSolvedStands(),
                referenceDataService.listCreneaux());
    }
}
