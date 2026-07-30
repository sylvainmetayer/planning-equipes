package dev.sylvain.planning.api;

import dev.sylvain.planning.domain.PlanningFestival;
import dev.sylvain.planning.service.ConstraintAnalysisStore;
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

    @Inject
    ConstraintAnalysisStore analysisStore;

    /**
     * Lists the scenario files available in the {@code scenarios} folder so the
     * UI can offer them in a dropdown. Adding a file to that folder makes it
     * appear here with no code change.
     */
    @GET
    @Path("/planning/scenarios")
    public java.util.List<String> scenarios() {
        return planningService.listerScenarios();
    }

    @GET
    @Path("/planning/sample")
    public PlanningFestival sample(@QueryParam("name") String name) {
        return planningService.construireExemple(name);
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
     * Empties the database: every stand, timeslot, animator, assignment and ad
     * hoc constraint is wiped, without loading any scenario. Reference data is
     * then seeded from the Data setup page. Returns an all-zero summary since
     * nothing remains.
     */
    @POST
    @Path("/planning/reset")
    @Consumes(MediaType.WILDCARD)
    public ResetSummary reset() {
        persistenceService.clearDatabase();
        return new ResetSummary(0, 0, 0, 0);
    }

    public record ResetSummary(int animateurs, int stands, int creneaux, int postes) {
    }

    /**
     * Read-only view of the last solved planning stored in the database.
     * Used by the calendar pages and the exports so that browsing the app
     * never starts a solver run.
     */
    @GET
    @Path("/planning/persisted")
    public PlanningFestival persistedPlanning() {
        return persistenceService.loadPersistedPlanning();
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
        PlanningDiagnostic diagnostic = planningService.analyser(planningFestival, secondsLimit);
        analysisStore.record(diagnostic);
        return diagnostic;
    }
}
