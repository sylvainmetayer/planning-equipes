package dev.sylvain.planning.api;

import dev.sylvain.planning.domain.PlanningFestival;
import dev.sylvain.planning.service.PlanningService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class PlanningResource {

    @Inject
    PlanningService planningService;

    @GET
    @Path("/planning/sample")
    public PlanningFestival sample() {
        return planningService.construireExemple();
    }

    @POST
    @Path("/solve")
    public PlanningFestival solve(PlanningFestival planningFestival) {
        return planningService.resoudre(planningFestival);
    }
}
