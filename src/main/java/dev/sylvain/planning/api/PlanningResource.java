package dev.sylvain.planning.api;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.service.PlanningPersistenceService;
import dev.sylvain.planning.service.PlanningService;
import dev.sylvain.planning.service.ReferenceDataChangeTracker;
import dev.sylvain.planning.service.SolvePipeline;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class PlanningResource {

    @Inject
    SolvePipeline pipeline;

    @Inject
    PlanningService planningService;

    @Inject
    PlanningPersistenceService persistenceService;

    @Inject
    ReferenceDataChangeTracker changeTracker;

    /**
     * Lists the scenario files available in the {@code scenarios} folder so the
     * UI can offer them in a dropdown. Adding a file to that folder makes it
     * appear here with no code change.
     */
    @GET
    @Path("/planning/scenarios")
    public List<String> scenarios() {
        return planningService.listScenarios();
    }

    @GET
    @Path("/planning/sample")
    public PlanningEvenement sample(@QueryParam("name") String name) {
        return planningService.buildExample(name);
    }

    /**
     * Exports the currently persisted reference data (stands, créneaux,
     * animateurs, and the seat list they imply) as a downloadable scenario YAML
     * file, in the same format read by the "Load sample planning" scenarios.
     */
    @GET
    @Path("/planning/export-scenario")
    @Produces("application/x-yaml")
    public Response exportScenario() {
        String yaml = planningService.exportScenarioYaml();
        String filename = "scenario-" + LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE) + ".yaml";
        return Response.ok(yaml)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .build();
    }

    /**
     * Real scale of the problem the next solve will build, computed the exact
     * same way {@code buildFromReferenceData} does for an actual solve
     * ({@code postes.size()} is Timefold's entity count, {@code animateurs.size()}
     * its value count) — so this never drifts from what the solver logs report,
     * unlike a naive stands × créneaux guess would. Returns all-zero rather than
     * an error when reference data isn't loaded yet, since this only feeds a
     * read-only summary card, not an actual solve.
     */
    @GET
    @Path("/planning/volumetrie")
    public VolumeView volumes() {
        try {
            PlanningEvenement evenement = planningService.buildFromReferenceData();
            return new VolumeView(evenement.getAnimateurs().size(), evenement.getPostes().size(),
                    evenement.getContraintesAdHoc().size());
        } catch (IllegalStateException e) {
            return new VolumeView(0, 0, 0);
        }
    }

    public record VolumeView(int animateurCount, int posteCount, int contrainteAdHocCount) {
    }

    @POST
    @Path("/solve")
    public PlanningEvenement solve(PlanningEvenement planningEvenement,
            @QueryParam("seconds") Long secondsLimit) {
        // Exactly the same path as the asynchronous solves — snapshot of the
        // previous plan, solve, persistence, diagnosis, Contraintes screen,
        // KPIs, announcement. This is where the incomplete copy used to live.
        return pipeline.execute(planningEvenement, secondsLimit).planning();
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
    public PlanningEvenement persistedPlanning() {
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
     * Which groupe de créneaux the last persisted solve was computed for, and
     * when, plus when reference data (stands, animateurs, créneaux, constraint
     * toggles, ...) was last changed. Lets the UI warn when the active group has
     * since changed, or when the data has been edited since that solve, so the
     * persisted planning shown by the calendars may be stale.
     * {@code solved} is {@code false} when nothing has ever been solved.
     */
    @GET
    @Path("/planning/persisted/resolution")
    public PlanningResolutionView persistedResolution() {
        PlanningPersistenceService.PlanningResolution resolution = persistenceService.loadResolution();
        Instant derniereModificationDonnees = changeTracker.lastModifiedAt();
        if (resolution == null) {
            return new PlanningResolutionView(false, null, derniereModificationDonnees);
        }
        return new PlanningResolutionView(true, resolution.resoluLe(), derniereModificationDonnees);
    }

    public record PlanningResolutionView(boolean solved, Instant resoluLe, Instant derniereModificationDonnees) {
    }

}
