package dev.sylvain.planning.api;

import dev.sylvain.planning.domain.PlanningFestival;
import dev.sylvain.planning.service.PlanningHoursService;
import dev.sylvain.planning.service.PlanningHoursService.HeuresRapport;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/planning/hours")
@Consumes(MediaType.APPLICATION_JSON)
public class PlanningHoursResource {

    @Inject
    PlanningHoursService heuresPlanningService;

    @POST
    @Produces(MediaType.APPLICATION_JSON)
    public HeuresRapport compute(PlanningFestival planningFestival) {
        return heuresPlanningService.compute(planningFestival);
    }

    @POST
    @Path("/export")
    @Produces("text/csv")
    public Response exportCsv(PlanningFestival planningFestival) {
        HeuresRapport rapport = heuresPlanningService.compute(planningFestival);
        String csv = heuresPlanningService.generateCsv(rapport);
        return Response.ok(csv)
                .type("text/csv; charset=utf-8")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"heures-planning.csv\"")
                .build();
    }
}
