package dev.sylvain.planning.api;

import java.util.List;

import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.PlanningFestival;
import dev.sylvain.planning.service.PlanningService;
import dev.sylvain.planning.service.ReferenceDataService;
import dev.sylvain.planning.service.StaffingAnalyzer;
import dev.sylvain.planning.service.StaffingAnalyzer.StaffingSummary;
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
 * horaires récurrents, familles de relais, effectif réduit pendant les pauses).
 * Computing it in the browser from stands × créneaux did drift, badly — see
 * {@link StaffingAnalyzer}.</p>
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
    public StaffingSummary analyser() {
        ParametresLegaux parametres = referenceDataService.getParametresLegaux();
        List<dev.sylvain.planning.domain.PosteAffectation> postes;
        try {
            PlanningFestival festival = planningService.construireDepuisReferenceData();
            postes = festival.getPostes();
        } catch (IllegalStateException e) {
            postes = List.of();
        }
        return staffingAnalyzer.analyser(postes, parametres.getDureeHebdomadaireMaxMinutes(),
                parametres.getPauseMinimaleEntreVacationsMinutes());
    }
}
