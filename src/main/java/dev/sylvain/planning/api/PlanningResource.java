package dev.sylvain.planning.api;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

import dev.sylvain.planning.domain.PlanningFestival;
import dev.sylvain.planning.service.ConstraintAnalysisStore;
import dev.sylvain.planning.service.PlanningPersistenceService;
import dev.sylvain.planning.service.PlanningService;
import dev.sylvain.planning.service.PlanningService.PlanningDiagnostic;
import dev.sylvain.planning.service.ReferenceDataChangeTracker;
import dev.sylvain.planning.service.ResolutionPipeline;
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
    ResolutionPipeline pipeline;

    @Inject
    PlanningService planningService;

    @Inject
    PlanningPersistenceService persistenceService;

    @Inject
    ConstraintAnalysisStore analysisStore;

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
        return planningService.listerScenarios();
    }

    @GET
    @Path("/planning/sample")
    public PlanningFestival sample(@QueryParam("name") String name) {
        return planningService.construireExemple(name);
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
        String yaml = planningService.exporterScenarioYaml();
        String filename = "scenario-" + LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE) + ".yaml";
        return Response.ok(yaml)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .build();
    }

    /**
     * Real scale of the problem the next solve will build, computed the exact
     * same way {@code construireDepuisReferenceData} does for an actual solve
     * ({@code postes.size()} is Timefold's entity count, {@code animateurs.size()}
     * its value count) — so this never drifts from what the solver logs report,
     * unlike a naive stands × créneaux guess would. Returns all-zero rather than
     * an error when reference data isn't loaded yet, since this only feeds a
     * read-only summary card, not an actual solve.
     */
    @GET
    @Path("/planning/volumetrie")
    public VolumetrieView volumetrie() {
        try {
            PlanningFestival festival = planningService.construireDepuisReferenceData();
            return new VolumetrieView(festival.getAnimateurs().size(), festival.getPostes().size(),
                    festival.getContraintesAdHoc().size());
        } catch (IllegalStateException e) {
            return new VolumetrieView(0, 0, 0);
        }
    }

    public record VolumetrieView(int animateurCount, int posteCount, int contrainteAdHocCount) {
    }

    @POST
    @Path("/solve")
    public PlanningFestival solve(PlanningFestival planningFestival,
            @QueryParam("seconds") Long secondsLimit) {
        // Exactement le même chemin que les solves asynchrones — capture du plan
        // précédent, résolution, persistance, diagnostic, écran Contraintes,
        // KPI, annonce. C'est ici que la copie incomplète vivait.
        return pipeline.executer(planningFestival, secondsLimit).planning();
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
