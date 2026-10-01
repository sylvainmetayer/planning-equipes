package dev.sylvain.planning.api;

import dev.sylvain.planning.service.analyse.StaffingAnalyzer;
import dev.sylvain.planning.service.analyse.StaffingAnalyzer.StaffingSummary;
import dev.sylvain.planning.service.analyse.StaffingService;
import dev.sylvain.planning.service.solve.StaffingVerificationService;
import dev.sylvain.planning.service.solve.StaffingVerificationService.StaffingVerification;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

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

    private final StaffingVerificationService verificationService;

    @Inject
    public StaffingResource(StaffingService staffingService, StaffingVerificationService verificationService) {
        this.staffingService = staffingService;
        this.verificationService = verificationService;
    }

    /**
     * The made-up team a check staffs the seats with, and the time it is given.
     *
     * @param majeurs       the adults; {@code null} or absent for the floor the
     *                      screen shows, less the minors
     * @param mineurs       the minors, aged sixteen on the first day; absent for none
     * @param dureeSecondes the time the solve is given at most, from 10 s to an
     *                      hour; absent for the server's default
     */
    public record VerificationRequest(Integer majeurs, Integer mineurs, Long dureeSecondes) {}

    /**
     * Never an error on an empty edition: this only feeds a read-only screen,
     * which a new user opens precisely before entering anything, and the
     * payload names what is missing.
     */
    @GET
    public StaffingSummary analyze() {
        return staffingService.analyzeEdition();
    }

    /**
     * Starts a solve of the edition's seats by a made-up team, to tell whether
     * the floor suffices — {@code 202}, the solve running in the background.
     * {@code 409} while another check runs, in any edition.
     */
    @POST
    @Path("/verification")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response verify(VerificationRequest request) {
        StaffingVerification started = request == null
                ? verificationService.start(null, null, null)
                : verificationService.start(request.majeurs(), request.mineurs(), request.dureeSecondes());
        return Response.accepted(started).build();
    }

    /** The last check of the edition, running or finished; {@code 204} when none was ever run. */
    @GET
    @Path("/verification")
    public Response verification() {
        return verificationService
                .current()
                .map(verification -> Response.ok(verification).build())
                .orElseGet(() -> Response.noContent().build());
    }
}
