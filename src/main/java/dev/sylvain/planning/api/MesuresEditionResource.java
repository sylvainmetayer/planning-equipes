package dev.sylvain.planning.api;

import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.analyse.KpiHistoriqueService;
import dev.sylvain.planning.service.analyse.KpiHistoriqueService.KpiHistoriqueEntry;
import dev.sylvain.planning.service.analyse.RealisedVsPlanned;
import dev.sylvain.planning.service.analyse.RealisedVsPlanned.CellDetail;
import dev.sylvain.planning.service.analyse.RealisedVsPlanned.PreviousEdition;
import dev.sylvain.planning.service.analyse.RealisedVsPlannedService;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * The measures of an edition (since 4.0, one table: {@code mesure_edition}):
 * the <b>indicators</b> of its solves, kept forever and listed across
 * <b>all</b> editions — comparing 2025 to 2026 is the point, and a row survives
 * the deletion of its edition —, and the <b>realised</b> against the published
 * plan, of the edition in force.
 *
 * <p>{@code /kpi/historique} and {@code /planning/realise} stay as deprecated
 * aliases until 5.0 ({@link KpiResource}, {@link RealiseResource}).</p>
 *
 * <p>The indicators' deletion takes an id, not an edition: the row deleted is
 * one of those the caller has just been shown — including the rows of an
 * edition that no longer exists, which nothing else could ever clean up.</p>
 */
@Path("/mesures-edition")
@Produces(MediaType.APPLICATION_JSON)
public class MesuresEditionResource {

    private final KpiHistoriqueService kpiHistoriqueService;

    private final RealisedVsPlannedService realisedService;

    @Inject
    public MesuresEditionResource(KpiHistoriqueService kpiHistoriqueService, RealisedVsPlannedService realisedService) {
        this.kpiHistoriqueService = kpiHistoriqueService;
        this.realisedService = realisedService;
    }

    /** Every edition's indicators, one row per finished solve, newest first. */
    @GET
    @Path("/indicateurs")
    public List<KpiHistoriqueEntry> listIndicators() {
        return kpiHistoriqueService.list();
    }

    @DELETE
    @Path("/indicateurs/{id}")
    public Response deleteIndicator(@PathParam("id") long id) {
        return KpiResource.delete(kpiHistoriqueService, id);
    }

    /**
     * The grid, its totals and what the measure rests on. Before the first
     * publication the answer is still {@code 200}, with
     * {@code referenceAvailable} false: nothing to compare is an answer.
     */
    @GET
    @Path("/realise")
    public RealisedVsPlanned report() {
        return realisedService.report();
    }

    /** One cell: the shifts of that stand and day whose holder or hours moved, holders named. */
    @GET
    @Path("/realise/detail")
    public CellDetail detail(@QueryParam("jour") String jour, @QueryParam("stand") String stand) {
        return detail(realisedService, jour, stand);
    }

    /** The grid as a CSV: stand, day and counters, never a person. */
    @GET
    @Path("/realise/export")
    @NotForLecteur
    @Produces("text/csv")
    public Response exportCsv() {
        return CsvDownload.attachment(realisedService.csv(), "realise-vs-planifie.csv");
    }

    /** The measure the previous edition left, read by the Versions page and the Besoin tab. */
    @GET
    @Path("/realise/precedente")
    public PreviousEdition previousEdition() {
        return realisedService.previousEdition();
    }

    static CellDetail detail(RealisedVsPlannedService service, String jour, String stand) {
        if (stand == null || stand.isBlank()) {
            throw new BusinessError.Invalid("Le stand est obligatoire.");
        }
        return service.detail(day(jour), stand);
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
