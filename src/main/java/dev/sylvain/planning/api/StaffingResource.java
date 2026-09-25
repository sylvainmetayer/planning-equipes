package dev.sylvain.planning.api;

import dev.sylvain.planning.service.analyse.StaffingAnalyzer;
import dev.sylvain.planning.service.analyse.StaffingAnalyzer.StaffingSummary;
import dev.sylvain.planning.service.analyse.StaffingService;
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
 * they are built exactly like {@code POST /api/solve/async/reference-data}
 * builds them, so the count never drifts from the real one (résolution des
 * horaires récurrents, families de relais, effectif réduit pendant les pauses).
 * Computing it in the browser from stands × créneaux did drift, badly — see
 * {@link StaffingAnalyzer}. Seats only, though: the problem itself refuses an
 * edition without animateur, and this screen is meant to be read before any is
 * entered (issue #416) — see {@link StaffingService}.</p>
 *
 * <p>The same payload carries the bottleneck per game category — the bounds
 * of a single typologie against the animateurs who declare it. It is the same
 * computation on the same seats, so it travels with them rather than through
 * a second endpoint rebuilding the whole problem.</p>
 */
@Path("/staffing")
@Produces(MediaType.APPLICATION_JSON)
public class StaffingResource {

    private final StaffingService staffingService;

    @Inject
    public StaffingResource(StaffingService staffingService) {
        this.staffingService = staffingService;
    }

    /**
     * Never an error on an empty edition: this only feeds a read-only screen,
     * which a new user opens precisely before entering anything, and the
     * payload names what is missing.
     */
    @GET
    public StaffingSummary analyze() {
        return staffingService.analyzeEdition();
    }
}
