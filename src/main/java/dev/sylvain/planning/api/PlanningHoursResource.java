package dev.sylvain.planning.api;

import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.service.analyse.PlanningHoursService;
import dev.sylvain.planning.service.analyse.PlanningHoursService.HeuresRapport;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/planning/hours")
@Consumes(MediaType.APPLICATION_JSON)
public class PlanningHoursResource {

    private final PlanningHoursService heuresPlanningService;

    @Inject
    public PlanningHoursResource(PlanningHoursService heuresPlanningService) {
        this.heuresPlanningService = heuresPlanningService;
    }

    @POST
    @Produces(MediaType.APPLICATION_JSON)
    public HeuresRapport compute(PlanningEvenement planningEvenement) {
        return heuresPlanningService.compute(planningEvenement);
    }

    @POST
    @Path("/export")
    @Produces("text/csv")
    public Response exportCsv(PlanningEvenement planningEvenement) {
        HeuresRapport rapport = heuresPlanningService.compute(planningEvenement);
        String csv = heuresPlanningService.generateCsv(rapport);
        return CsvDownload.attachment(csv, "heures-planning.csv");
    }
}
