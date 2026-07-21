package dev.sylvain.planning.api;

import dev.sylvain.planning.domain.PlanningFestival;
import dev.sylvain.planning.service.PlanningPersistenceService;
import dev.sylvain.planning.service.PlanningService;
import dev.sylvain.planning.service.PlanningService.PlanningDiagnostic;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class PlanningResource {

    @Inject
    PlanningService planningService;

    @Inject
    PlanningPersistenceService persistenceService;

    @GET
    @Path("/planning/sample")
    public PlanningFestival sample() {
        return planningService.construireExemple();
    }

    @POST
    @Path("/solve")
    public PlanningFestival solve(PlanningFestival planningFestival,
            @QueryParam("seconds") Long secondsLimit) {
        PlanningFestival solved = planningService.resoudre(planningFestival, secondsLimit);
        persistenceService.persist(solved);
        return solved;
    }

    /**
     * Reports how many assignment rows are currently stored in the database,
     * so callers can confirm the last solve was persisted.
     */
    @GET
    @Path("/planning/persisted/count")
    public PersistenceStatus persistedCount() {
        return new PersistenceStatus(persistenceService.countPersistedAssignments());
    }

    public record PersistenceStatus(int assignments) {
    }

    /**
     * Solve and return a per-constraint breakdown of the resulting score.
     * Useful when {@code /api/solve} finishes with a non-zero hard score:
     * this endpoint tells you which constraint(s) are still violated and by
     * how much, instead of just returning the raw score.
     */
    @POST
    @Path("/solve/analyze")
    public PlanningDiagnostic analyze(PlanningFestival planningFestival,
            @QueryParam("seconds") Long secondsLimit) {
        return planningService.analyser(planningFestival, secondsLimit);
    }
}
