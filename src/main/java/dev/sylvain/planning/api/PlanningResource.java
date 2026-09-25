package dev.sylvain.planning.api;

import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.service.ReferenceDataChangeTracker;
import dev.sylvain.planning.service.analyse.GroupedArrivalAnalyzer;
import dev.sylvain.planning.service.analyse.WalkSequenceAnalyzer;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import dev.sylvain.planning.service.solve.PlanningService;
import dev.sylvain.planning.service.solve.ProblemScaleService;
import dev.sylvain.planning.service.solve.SolvePipeline;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class PlanningResource {

    private final SolvePipeline pipeline;

    private final PlanningService planningService;

    private final PlanningPersistenceService persistenceService;

    private final ReferenceDataChangeTracker changeTracker;

    private final ProblemScaleService problemScaleService;

    private final ReferenceDataService referenceDataService;

    private final WalkSequenceAnalyzer walkSequenceAnalyzer;

    private final GroupedArrivalAnalyzer groupedArrivalAnalyzer;

    @Inject
    public PlanningResource(
            SolvePipeline pipeline,
            PlanningService planningService,
            PlanningPersistenceService persistenceService,
            ReferenceDataChangeTracker changeTracker,
            ProblemScaleService problemScaleService,
            ReferenceDataService referenceDataService,
            WalkSequenceAnalyzer walkSequenceAnalyzer,
            GroupedArrivalAnalyzer groupedArrivalAnalyzer) {
        this.pipeline = pipeline;
        this.planningService = planningService;
        this.persistenceService = persistenceService;
        this.changeTracker = changeTracker;
        this.problemScaleService = problemScaleService;
        this.referenceDataService = referenceDataService;
        this.walkSequenceAnalyzer = walkSequenceAnalyzer;
        this.groupedArrivalAnalyzer = groupedArrivalAnalyzer;
    }

    /**
     * Lists the scenario files available in the {@code scenarios} folder so the
     * Débogage screen can offer them in a dropdown. Adding a file to that
     * folder makes it appear here with no code change — which is why the folder
     * is flat and unique: a name carrying a path component is refused by
     * {@code ScenarioYamlReader.scenarioPath}.
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
        String filename =
                "scenario-" + LocalDate.now(ZoneId.systemDefault()).format(DateTimeFormatter.ISO_LOCAL_DATE) + ".yaml";
        return Response.ok(yaml)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .build();
    }

    /** Real scale of the problem the next solve will build — see {@link ProblemScaleService}. */
    @GET
    @Path("/planning/volumetrie")
    public ProblemScaleService.ProblemScale volumes() {
        return problemScaleService.compute();
    }

    @POST
    @Path("/solve")
    public PlanningEvenement solve(PlanningEvenement planningEvenement, @QueryParam("seconds") Long secondsLimit) {
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

    @Schema(requiredProperties = {"animateurs", "creneaux", "postes", "stands"})
    public record ResetSummary(int animateurs, int stands, int creneaux, int postes) {}

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

    /**
     * The tight walks of the persisted plan: two consecutive seats of one
     * animateur, on two emplacements, whose gap does not leave the time to walk
     * from one to the other — or leaves it only by eating the legal break. A
     * reading of the persisted plan (ADR 0014), under the edition's current
     * walking settings, never a solve; empty, and saying why, when no
     * emplacement carries coordinates.
     */
    @GET
    @Path("/planning/enchainements")
    public WalkSequenceAnalyzer.WalkSequenceReport walks() {
        return walkSequenceAnalyzer.analyze(
                persistenceService.loadPersistedPlanning(),
                referenceDataService.getParametresQualite(),
                referenceDataService.getParametresLegaux());
    }

    /**
     * Day by day, whether each grouped arrival ({@code ARRIVEE_GROUPEE}) of
     * the persisted plan arrives and leaves together, under the edition's
     * current tolerance: the days a member works alone, and the days the first
     * arrivals or the last departures are too far apart. A reading of the
     * persisted plan, never a solve; ids only.
     */
    @GET
    @Path("/planning/arrivees-groupees")
    public GroupedArrivalAnalyzer.GroupedArrivalReport groupedArrivals() {
        return groupedArrivalAnalyzer.analyze(
                persistenceService.loadPersistedPlanning(),
                referenceDataService.listContraintesAdHoc(),
                referenceDataService.getParametresQualite());
    }

    @Schema(requiredProperties = {"assignments"})
    public record PersistenceStatus(int assignments) {}

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

    @Schema(requiredProperties = {"solved"})
    public record PlanningResolutionView(boolean solved, Instant resoluLe, Instant derniereModificationDonnees) {}
}
