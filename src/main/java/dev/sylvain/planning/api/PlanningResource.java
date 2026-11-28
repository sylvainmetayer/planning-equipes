package dev.sylvain.planning.api;

import ai.timefold.solver.core.api.score.HardMediumSoftScore;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ConstraintToggle;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.FenetreRepas;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.ParametresQualite;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.QuotaTypologie;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.VerrouillagePlanning;
import dev.sylvain.planning.service.ReferenceDataChangeTracker;
import dev.sylvain.planning.service.analyse.GroupedArrivalAnalyzer;
import dev.sylvain.planning.service.analyse.WalkSequenceAnalyzer;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import dev.sylvain.planning.service.solve.PlanningService;
import dev.sylvain.planning.service.solve.ProblemScaleService;
import dev.sylvain.planning.service.solve.SolvePipeline;
import dev.sylvain.planning.service.solve.WipeBackupService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    private final WipeBackupService wipeBackup;

    @Inject
    public PlanningResource(
            SolvePipeline pipeline,
            PlanningService planningService,
            PlanningPersistenceService persistenceService,
            ReferenceDataChangeTracker changeTracker,
            ProblemScaleService problemScaleService,
            ReferenceDataService referenceDataService,
            WalkSequenceAnalyzer walkSequenceAnalyzer,
            GroupedArrivalAnalyzer groupedArrivalAnalyzer,
            WipeBackupService wipeBackup) {
        this.pipeline = pipeline;
        this.planningService = planningService;
        this.persistenceService = persistenceService;
        this.changeTracker = changeTracker;
        this.problemScaleService = problemScaleService;
        this.referenceDataService = referenceDataService;
        this.walkSequenceAnalyzer = walkSequenceAnalyzer;
        this.groupedArrivalAnalyzer = groupedArrivalAnalyzer;
        this.wipeBackup = wipeBackup;
    }

    /**
     * Lists the scenario files available in the {@code scenarios} folder so the
     * Fichiers screen can offer them as examples. Adding a file to that
     * folder makes it appear here with no code change — which is why the folder
     * is flat and unique: a name carrying a path component is refused by
     * {@code ScenarioYamlReader.scenarioPath}. The {@code gamme-*} and
     * {@code extreme-*} files are shipped and loadable by name but not listed.
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
    @NotForLecteur
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

    /** The scenarios saved before this edition was last emptied (ADR 0084), most recent first. */
    @GET
    @Path("/planning/sauvegardes-avant-vidage")
    public List<WipeBackupService.WipeBackup> wipeBackups() {
        return wipeBackup.list();
    }

    /**
     * Restores a backup in one gesture — the edition backed up first, the
     * scenario re-imported, the plan and locks put back on it (ADR 0084).
     * Not atomic: a failure says at which step, and which backup undoes it.
     */
    @POST
    @Path("/planning/sauvegardes-avant-vidage/{id}/restauration")
    @Consumes(MediaType.WILDCARD)
    public WipeBackupService.Restoration restoreWipeBackup(@PathParam("id") long id) {
        return wipeBackup.restore(id);
    }

    /**
     * Deletes one backup of the edition: nothing deletes them otherwise but
     * the deletion of the edition (ADR 0084), and erasing a person from the
     * backups that carry them goes through here.
     */
    @DELETE
    @Path("/planning/sauvegardes-avant-vidage/{id}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteWipeBackup(@PathParam("id") long id) {
        wipeBackup.delete(id);
        return Response.noContent().build();
    }

    /** One of them, as a scenario file: what the restore re-imports, to read before restoring it. */
    @GET
    @Path("/planning/sauvegardes-avant-vidage/{id}")
    @NotForLecteur
    @Produces("application/x-yaml")
    public Response downloadWipeBackup(@PathParam("id") long id) {
        return Response.ok(wipeBackup.loadContent(id))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"sauvegarde-" + id + ".yaml\"")
                .build();
    }

    /**
     * Read-only view of the last solved planning stored in the database, each
     * stand, timeslot and animateur written once and every seat naming them by
     * id: the full form repeated them in every seat, 4.5 MB for a demo plan at
     * each opening of the Planning screen. Used by the calendar pages and the
     * exports, which rebuild the full form, so that browsing the app never
     * starts a solver run.
     */
    @GET
    @Path("/planning/persisted")
    public CompactPlanning persistedPlanning() {
        return CompactPlanning.of(persistenceService.loadPersistedPlanning());
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
     * A seat of {@link CompactPlanning}: what {@link PosteAffectation} says,
     * with its stand, timeslot and animateur by id.
     */
    @Schema(requiredProperties = {"id", "verrouille", "dureeEffectiveMinutes"})
    public record CompactSeat(
            String id,
            String standId,
            Long creneauId,
            String animateurId,
            boolean verrouille,
            int dureeEffectiveMinutes,
            LocalTime heureDebutEffective,
            LocalTime heureFinEffective,
            String suiteDe) {}

    /**
     * {@link PlanningEvenement} with its seats flattened: {@code stands} and
     * {@code creneaux} are the ones the seats stand on, each once, and
     * {@code animateurs} is the plan's own list, which every held seat names.
     */
    @Schema(requiredProperties = {"animateurs", "stands", "creneaux", "postes"})
    public record CompactPlanning(
            LocalDate dateDebutFestival,
            HardMediumSoftScore score,
            List<Animateur> animateurs,
            List<Stand> stands,
            List<Creneau> creneaux,
            List<CompactSeat> postes,
            List<ContrainteAdHoc> contraintesAdHoc,
            List<ParametresLegaux> parametresLegaux,
            List<ParametresQualite> parametresQualite,
            List<ConstraintToggle> constraintsDesactivees,
            List<QuotaTypologie> quotasTypologies,
            List<FenetreRepas> fenetresRepas,
            List<VerrouillagePlanning> verrouillages) {

        static CompactPlanning of(PlanningEvenement plan) {
            Map<String, Stand> stands = new LinkedHashMap<>();
            Map<Long, Creneau> creneaux = new LinkedHashMap<>();
            List<CompactSeat> seats = new ArrayList<>();
            for (PosteAffectation poste : plan.getPostes()) {
                Stand stand = poste.getStand();
                Creneau creneau = poste.getCreneau();
                Animateur animateur = poste.getAnimateur();
                if (stand != null) {
                    stands.putIfAbsent(stand.getId(), stand);
                }
                if (creneau != null) {
                    creneaux.putIfAbsent(creneau.getId(), creneau);
                }
                seats.add(new CompactSeat(
                        poste.getId(),
                        stand == null ? null : stand.getId(),
                        creneau == null ? null : creneau.getId(),
                        animateur == null ? null : animateur.getId(),
                        poste.isVerrouille(),
                        poste.getDureeEffectiveMinutes(),
                        poste.getHeureDebutEffective(),
                        poste.getHeureFinEffective(),
                        poste.getSuiteDe()));
            }
            return new CompactPlanning(
                    plan.getDateDebutFestival(),
                    plan.getScore(),
                    plan.getAnimateurs(),
                    List.copyOf(stands.values()),
                    List.copyOf(creneaux.values()),
                    seats,
                    plan.getContraintesAdHoc(),
                    plan.getParametresLegaux(),
                    plan.getParametresQualite(),
                    plan.getConstraintsDesactivees(),
                    plan.getQuotasTypologies(),
                    plan.getFenetresRepas(),
                    plan.getVerrouillages());
        }
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

    @Schema(requiredProperties = {"solved"})
    public record PlanningResolutionView(boolean solved, Instant resoluLe, Instant derniereModificationDonnees) {}
}
