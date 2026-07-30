package dev.sylvain.planning.api;

import dev.sylvain.planning.domain.PlanningFestival;
import dev.sylvain.planning.service.HeuresPlanningService;
import dev.sylvain.planning.service.HeuresPlanningService.HeuresRapport;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/api/planning/hours")
@Consumes(MediaType.APPLICATION_JSON)
public class PlanningHoursResource {

    @Inject
    HeuresPlanningService heuresPlanningService;

    @POST
    @Produces(MediaType.APPLICATION_JSON)
    public HeuresRapport calculer(PlanningFestival planningFestival) {
        return heuresPlanningService.calculer(planningFestival);
    }

    @POST
    @Path("/export")
    @Produces("text/csv")
    public Response exporterCsv(PlanningFestival planningFestival) {
        HeuresRapport rapport = heuresPlanningService.calculer(planningFestival);
        String csv = heuresPlanningService.genererCsv(rapport);
        return Response.ok(csv)
                .type("text/csv; charset=utf-8")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"heures-planning.csv\"")
                .build();
    }
}
