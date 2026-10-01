package dev.sylvain.planning.api;

import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.analyse.RealisedVsPlanned;
import dev.sylvain.planning.service.analyse.RealisedVsPlanned.CellDetail;
import dev.sylvain.planning.service.analyse.RealisedVsPlanned.PreviousEdition;
import dev.sylvain.planning.service.analyse.RealisedVsPlannedService;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * Réalisé vs planifié: the gap between the published plan and the plan held,
 * per stand and per day of the elapsed days. Reads only; the CSV is
 * journalled as an export like every file that leaves the application.
 */
@Path("/planning/realise")
@Produces(MediaType.APPLICATION_JSON)
public class RealiseResource {

    private final RealisedVsPlannedService realisedService;

    @Inject
    public RealiseResource(RealisedVsPlannedService realisedService) {
        this.realisedService = realisedService;
    }

    /**
     * The grid, its totals and what the measure rests on. Before the first
     * publication the answer is still {@code 200}, with
     * {@code referenceAvailable} false: nothing to compare is an answer.
     */
    @GET
    public RealisedVsPlanned report() {
        return realisedService.report();
    }

    /** One cell: the shifts of that stand and day whose holder or hours moved, holders named. */
    @GET
    @Path("/detail")
    public CellDetail detail(@QueryParam("jour") String jour, @QueryParam("stand") String stand) {
        if (stand == null || stand.isBlank()) {
            throw new BusinessError.Invalid("Le stand est obligatoire.");
        }
        return realisedService.detail(day(jour), stand);
    }

    /** The grid as a CSV: stand, day and counters, never a person. */
    @GET
    @Path("/export")
    @Produces("text/csv")
    public Response exportCsv() {
        return CsvDownload.attachment(realisedService.csv(), "realise-vs-planifie.csv");
    }

    /** The measure the previous edition left, read by the Versions page and the Besoin tab. */
    @GET
    @Path("/precedente")
    public PreviousEdition previousEdition() {
        return realisedService.previousEdition();
    }

    private static LocalDate day(String jour) {
        if (jour == null || jour.isBlank()) {
            throw new BusinessError.Invalid("La journée est obligatoire (AAAA-MM-JJ).");
        }
        try {
            return LocalDate.parse(jour);
        } catch (DateTimeParseException _) {
            throw new BusinessError.Invalid("Journée illisible : " + jour + " (attendu AAAA-MM-JJ)");
        }
    }
}
