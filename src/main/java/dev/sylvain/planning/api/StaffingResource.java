package dev.sylvain.planning.api;

import java.util.List;

import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.service.PlanningService;
import dev.sylvain.planning.service.ReferenceDataService;
import dev.sylvain.planning.service.analyse.StaffingAnalyzer;
import dev.sylvain.planning.service.analyse.StaffingAnalyzer.StaffingSummary;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Minimum staffing need computed on the current reference data, with no solve
 * involved — what the « Besoin en animateurs » screen displays.
 *
 * <p>The seats it reasons on are the ones an actual solve would have to fill:
 * the problem is built exactly like {@code POST /api/solve/async/reference-data}
 * builds it, so the count never drifts from the real one (résolution des
 * horaires récurrents, families de relais, effectif réduit pendant les pauses).
 * Computing it in the browser from stands × créneaux did drift, badly — see
 * {@link StaffingAnalyzer}.</p>
 *
 * <p>The same payload carries the bottleneck per game category — the bounds
 * of a single typologie against the animateurs who declare it. It is the same
 * computation on the same seats, so it travels with them rather than through
 * a second endpoint rebuilding the whole problem.</p>
 */
@Path("/staffing")
@Produces(MediaType.APPLICATION_JSON)
public class StaffingResource {

    @Inject
    PlanningService planningService;

    @Inject
    ReferenceDataService referenceDataService;

    @Inject
    StaffingAnalyzer staffingAnalyzer;

    /**
     * Returns an empty summary rather than an error when no reference data is
     * loaded yet: this only feeds a read-only screen, which a new user opens
     * precisely before entering anything.
     */
    @GET
    public StaffingSummary analyze() {
        ParametresLegaux parametres = referenceDataService.getParametresLegaux();
        List<dev.sylvain.planning.domain.PosteAffectation> postes;
        try {
            PlanningEvenement evenement = planningService.buildFromReferenceData();
            postes = evenement.getPostes();
        } catch (IllegalStateException e) {
            postes = List.of();
        }
        return staffingAnalyzer.analyze(postes, referenceDataService.listAnimateurs(),
                referenceDataService.listTypologies(), parametres.getDureeHebdomadaireMaxMinutes(),
                parametres.getPauseMinimaleEntreVacationsMinutes());
    }
}
