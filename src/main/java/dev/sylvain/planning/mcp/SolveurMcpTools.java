package dev.sylvain.planning.mcp;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.ConstraintAnalysisStore;
import dev.sylvain.planning.service.ConstraintAnalysisStore.StoredAnalysis;
import dev.sylvain.planning.service.PlanningPersistenceService;
import dev.sylvain.planning.service.PlanningService;
import dev.sylvain.planning.service.ReplanificationScope;
import dev.sylvain.planning.service.SolverJobService;
import dev.sylvain.planning.service.SolverJobService.SolverBusyException;
import dev.sylvain.planning.service.SolverJobService.SolverJob;
import dev.sylvain.planning.solver.ConstraintCatalog;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * MCP tools to drive the solver (start/stop/status) and inspect its results,
 * including — per issue #107 — an explicit way to know exactly which hard
 * constraints are still broken after a run. Every solve/analyze here is
 * built server-side from the persisted reference data (like
 * {@code SolverJobResource#solveFromReferenceData}), since an AI assistant
 * has no practical way to construct the full {@code PlanningEvenement} JSON
 * body the raw REST endpoints expect.
 *
 * <p>The two solves go through the <b>replayable</b> submissions, exactly like
 * the screens do. Building the problem here instead would have cost the three
 * properties that come with deferring it: no queueing behind a running job, no
 * replay after a restart, and a problem frozen at the call rather than at the
 * start — an assistant that keeps preparing the edition while a run finishes
 * would have solved the edition as it stood before its own edits.</p>
 */
@EditionCiblee
@ApplicationScoped
public class SolveurMcpTools {

    @Inject
    SolverJobService solverJobService;

    @Inject
    PlanningService planningService;

    @Inject
    PlanningPersistenceService persistenceService;

    @Inject
    ConstraintAnalysisStore analysisStore;

    @Tool(description = "Lance une résolution en tâche de fond à partir des données de référence persistées "
            + "(stands, créneaux, animateurs). Renvoie l'id du job à interroger via statut_solveur. "
            + "Avec enFile, la résolution attend son tour au lieu d'être refusée quand le solveur est occupé.")
    JobView lancer_solveur(@ToolArg(description = "Durée max en secondes (défaut : configuration serveur)", required = false) Long secondes,
            @ToolArg(description = "Attendre son tour si le solveur est occupé, au lieu d'échouer", required = false) Boolean enFile,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        return submit(() -> solverJobService.submitSolveFromReferenceData(secondes, Boolean.TRUE.equals(enFile)));
    }

    @Tool(description = "Relance une résolution partielle à partir du planning persisté : tout ce qu'un "
            + "changement tardif n'a pas invalidé reste figé, seul le périmètre rouvert est recalculé — d'où un "
            + "budget bien plus court qu'une résolution complète (60 s par défaut). Sans périmètre, seul ce que "
            + "les changements ont invalidé est rouvert. Le périmètre ne touche pas les verrouillages : il ne "
            + "vaut que pour ce job.")
    JobView resoudre_incremental(
            @ToolArg(description = "Ids d'animateurs dont tous les postes sont rouverts", required = false) List<String> animateurIds,
            @ToolArg(description = "Jours (AAAA-MM-JJ) dont tous les postes sont rouverts", required = false) List<String> jours,
            @ToolArg(description = "Ids de stands dont tous les postes sont rouverts", required = false) List<String> standIds,
            @ToolArg(description = "Durée max en secondes (défaut 60)", required = false) Long secondes,
            @ToolArg(description = "Attendre son tour si le solveur est occupé, au lieu d'échouer", required = false) Boolean enFile,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        ReplanificationScope scope = new ReplanificationScope(
                animateurIds == null ? Set.of() : new LinkedHashSet<>(animateurIds),
                McpArgs.dates(jours, "jours"),
                standIds == null ? Set.of() : new LinkedHashSet<>(standIds));
        return submit(() -> solverJobService.submitSolveIncremental(secondes, scope,
                Boolean.TRUE.equals(enFile)));
    }

    /**
     * Turns the busy-solver refusal into a sentence naming the job in the way,
     * shared by both launches. The submission itself is a supplier so the
     * problem is built <b>inside</b> the job rather than here: that is what
     * makes a queued run solve the edition as it stands when its turn comes,
     * and what lets a restart replay it.
     */
    private JobView submit(Supplier<SolverJob> submission) {
        try {
            return toView(submission.get());
        } catch (SolverBusyException e) {
            throw new BusinessError.Conflict("Solveur déjà occupé par le job " + e.getActiveJob().getId()
                    + " : relancez avec enFile pour attendre son tour.");
        }
    }

    @Tool(description = "Lance une analyse (score détaillé par contrainte, sans persister) en tâche de fond à "
            + "partir des données de référence persistées.")
    JobView lancer_analyse(@ToolArg(description = "Durée max en secondes (défaut : configuration serveur)", required = false) Long secondes,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        try {
            return toView(solverJobService.submitAnalyze(planningService.buildFromReferenceData(), secondes));
        } catch (SolverBusyException e) {
            // No enFile here, unlike the two solves: an analyze carries its own
            // problem, so it is never queued and never replayed.
            throw new BusinessError.Conflict("Solveur déjà occupé par le job " + e.getActiveJob().getId());
        }
    }

    @Tool(description = "Arrête le job en cours (le solveur renvoie sa meilleure solution trouvée jusqu'ici). "
            + "Sans id, arrête le job actif s'il y en a un.")
    JobView arreter_solveur(@ToolArg(description = "Id du job à arrêter", required = false) String jobId) {
        String id = jobId != null ? jobId
                : solverJobService.findActive()
                        .map(SolverJob::getId)
                        .orElseThrow(() -> new NoSuchElementException("Aucun solveur actif"));
        return solverJobService.cancel(id)
                .map(SolveurMcpTools::toView)
                .orElseThrow(() -> new NoSuchElementException("Job introuvable : " + id));
    }

    @Tool(description = "Statut du job en cours, ou d'un job donné par son id. Sans id et sans job actif, indique qu'aucun solveur ne tourne.")
    JobView statut_solveur(@ToolArg(description = "Id du job à interroger", required = false) String jobId) {
        Optional<SolverJob> job = jobId != null ? solverJobService.find(jobId) : solverJobService.findActive();
        return job.map(SolveurMcpTools::toView).orElse(null);
    }

    @Tool(description = "Liste tous les jobs de résolution/analyse (en cours et terminés).")
    List<JobView> lister_jobs() {
        return solverJobService.list().stream().map(SolveurMcpTools::toView).toList();
    }

    @Tool(description = "Résultats de planification (postes affectés) pour un animateur donné, à partir du dernier "
            + "planning persisté en base. Ne renvoie que des ids de stand/créneau, jamais de données personnelles.")
    List<AffectationView> resultats_animateur(@ToolArg(description = "Id de l'animateur") String animateurId,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        PlanningEvenement planning = persistenceService.loadPersistedPlanning();
        if (planning == null || planning.getPostes() == null) {
            return List.of();
        }
        return planning.getPostes().stream()
                .filter(poste -> poste.getAnimateur() != null && animateurId.equals(poste.getAnimateur().getId()))
                .map(SolveurMcpTools::toView)
                .toList();
    }

    @Tool(description = "Supprime un job terminé de l'historique. Un job encore en cours doit d'abord être arrêté.")
    SuppressionResult supprimer_job(@ToolArg(description = "Id du job") String jobId) {
        if (!solverJobService.forget(jobId)) {
            throw new NoSuchElementException("Job introuvable ou encore en cours : " + jobId);
        }
        return new SuppressionResult(jobId, true);
    }

    @Tool(description = "Détaille les contraintes de niveau HARD encore violées lors de la dernière analyse "
            + "(solve ou analyze), avec le message de chaque violation. Liste vide si la dernière analyse est "
            + "entièrement faisable, ou s'il n'y a jamais eu d'analyse.")
    List<ViolationHardView> expliquer_echec_contraintes_dures(
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        StoredAnalysis analysis = analysisStore.latest();
        if (analysis == null) {
            return List.of();
        }
        var hardNames = ConstraintCatalog.definitions().stream()
                .filter(definition -> definition.niveau() == ConstraintCatalog.Niveau.HARD)
                .map(ConstraintCatalog.ConstraintDefinition::name)
                .toList();
        return analysis.diagnostic().contraintes().stream()
                .filter(diagnostic -> hardNames.contains(diagnostic.name()) && diagnostic.matchCount() > 0)
                .map(diagnostic -> new ViolationHardView(diagnostic.name(), diagnostic.matchCount(),
                        AnonymisationViolations.anonymiser(diagnostic.violations())))
                .toList();
    }

    private static JobView toView(SolverJob job) {
        return new JobView(job.getId(), job.getType().name(), job.getStatus().name(), job.getSecondsLimit(),
                job.getSubmittedAt(), job.getStartedAt(), job.getFinishedAt(), job.getElapsedSeconds(), job.getError(),
                job.getEditionId(), job.getEditionNom());
    }

    private static AffectationView toView(PosteAffectation poste) {
        return new AffectationView(poste.getId(),
                poste.getStand() == null ? null : poste.getStand().getId(),
                poste.getStand() == null ? null : poste.getStand().getNom(),
                poste.getCreneau() == null ? null : poste.getCreneau().getId(),
                poste.getCreneau() == null ? null : poste.getCreneau().getDate(),
                poste.getHeureDebutEffective(), poste.getHeureFinEffective());
    }

    /**
     * Statut/résultat d'un job, sans le corps volumineux du planning résolu (voir resultats_animateur pour ça).
     *
     * <p>The edition is part of the view because the job registry is global
     * while jobs are not: without it, {@code lister_jobs} would show two
     * editions' runs as one undifferentiated history (issue #181).</p>
     */
    public record JobView(String id, String type, String status, Long secondsLimit, Instant submittedAt,
            Instant startedAt, Instant finishedAt, long elapsedSeconds, String error, String editionId,
            String editionNom) {
    }

    public record AffectationView(String posteId, String standId, String standNom, Long creneauId,
            LocalDate date, LocalTime heureDebut, LocalTime heureFin) {
    }

    public record ViolationHardView(String contrainte, int nombreCorrespondances, List<String> violations) {
    }
}
