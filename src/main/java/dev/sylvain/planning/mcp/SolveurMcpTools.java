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
import dev.sylvain.planning.service.PlanningService.PlanningDiagnostic;
import dev.sylvain.planning.service.Reamorcage;
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
 * constraints are still broken after a run. Every solve here is built
 * server-side from the persisted reference data (like
 * {@code SolverJobResource#solveFromReferenceData}), since an AI assistant
 * has no practical way to construct the full {@code PlanningEvenement} JSON
 * body the raw REST endpoints expect.
 *
 * <p>Diagnosing does not go through the solver at all: {@code diagnostiquer_plan}
 * scores the plan already persisted. It replaces a tool that launched a full
 * solve and threw its result away, which cost minutes to describe a plan no
 * other tool would ever report on.</p>
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
    PlanningPersistenceService persistenceService;

    @Inject
    ConstraintAnalysisStore analysisStore;

    @Tool(description = "Lance une résolution en tâche de fond à partir des données de référence persistées "
            + "(stands, créneaux, animateurs). Renvoie l'id du job à interroger via statut_solveur. "
            + "Avec enFile, la résolution attend son tour au lieu d'être refusée quand le solveur est occupé. "
            + "Par défaut elle repart du plan enregistré s'il en existe un, sans rien figer : un calcul de zéro "
            + "perd la qualité déjà atteinte, demandez-le explicitement (reamorcage=AUCUN).",
            annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = true,
                    idempotentHint = false, openWorldHint = false))
    JobView lancer_solveur(@ToolArg(description = "Durée max en secondes (défaut : configuration serveur)", required = false) Long secondes,
            @ToolArg(description = "Attendre son tour si le solveur est occupé, au lieu d'échouer", required = false) Boolean enFile,
            @ToolArg(description = "Point de départ : AUTO (défaut, repart du plan enregistré s'il existe), "
                    + "PLAN_COURANT (échoue s'il n'y a pas de plan), AUCUN (calcul de zéro)", required = false) String reamorcage,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        Reamorcage depart = Reamorcage.parse(reamorcage);
        return submit(() -> solverJobService.submitSolveFromReferenceData(secondes, Boolean.TRUE.equals(enFile), depart));
    }

    @Tool(description = "Relance une résolution partielle à partir du planning persisté : tout ce qu'un "
            + "changement tardif n'a pas invalidé reste figé, seul le périmètre rouvert est recalculé — d'où un "
            + "budget bien plus court qu'une résolution complète (60 s par défaut). Sans périmètre, seul ce que "
            + "les changements ont invalidé est rouvert. Le périmètre ne touche pas les verrouillages : il ne "
            + "vaut que pour ce job.",
            annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = true,
                    idempotentHint = false, openWorldHint = false))
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

    /**
     * Diagnoses the plan that is actually persisted, rather than solving a
     * fresh one to diagnose that instead: the score breakdown an assistant
     * needs describes the plan the other tools report on
     * ({@code etat_planning}, {@code lister_affectations}), and costs one
     * score calculation instead of a full solve.
     *
     * <p>Declared read-only, and it is: the only thing it writes is the
     * in-memory analysis the Contraintes screen reads, a pure function of the
     * plan it just read. No business data changes, so a client is right to
     * call it without asking.</p>
     */
    @Tool(description = "Diagnostic du planning persisté : score global et score de chaque contrainte, nombre de "
            + "correspondances, postes non pourvus. Recalculé à la demande sur le plan en base, avec les "
            + "contraintes et pondérations actives du moment — aucune résolution n'est lancée. Pour le détail "
            + "des violations dures, enchaîner avec expliquer_echec_contraintes_dures.",
            annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    DiagnosticPlanView diagnostiquer_plan(
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        StoredAnalysis analyse = analysisStore.refreshFromPersistedPlan();
        if (analyse == null) {
            throw new IllegalStateException("Aucun planning persisté : lancez d'abord une résolution.");
        }
        PlanningDiagnostic diagnostic = analyse.diagnostic();
        return new DiagnosticPlanView(diagnostic.score(), diagnostic.hardScore(), diagnostic.postesNonPourvus(),
                diagnostic.contraintes().stream()
                        .map(contrainte -> new ContrainteScoreView(contrainte.name(), contrainte.score(),
                                contrainte.matchCount()))
                        .toList());
    }

    @Tool(description = "Arrête le job en cours (le solveur renvoie sa meilleure solution trouvée jusqu'ici). "
            + "Sans id, arrête le job actif s'il y en a un.",
            annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    JobView arreter_solveur(@ToolArg(description = "Id du job à arrêter", required = false) String jobId) {
        String id = jobId != null ? jobId
                : solverJobService.findActive()
                        .map(SolverJob::getId)
                        .orElseThrow(() -> new NoSuchElementException("Aucun solveur actif"));
        return solverJobService.cancel(id)
                .map(SolveurMcpTools::toView)
                .orElseThrow(() -> new NoSuchElementException("Job introuvable : " + id));
    }

    @Tool(description = "Statut du job en cours, ou d'un job donné par son id. Sans id et sans job actif, indique qu'aucun solveur ne tourne.",
            annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    JobView statut_solveur(@ToolArg(description = "Id du job à interroger", required = false) String jobId) {
        Optional<SolverJob> job = jobId != null ? solverJobService.find(jobId) : solverJobService.findActive();
        return job.map(SolveurMcpTools::toView).orElse(null);
    }

    @Tool(description = "Liste tous les jobs de résolution/analyse (en cours et terminés).",
            annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    List<JobView> lister_jobs() {
        return solverJobService.list().stream().map(SolveurMcpTools::toView).toList();
    }

    @Tool(description = "Résultats de planification (postes affectés) pour un animateur donné, à partir du dernier "
            + "planning persisté en base. Ne renvoie que des ids de stand/créneau, jamais de données personnelles.",
            annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
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

    @Tool(description = "Supprime un job terminé de l'historique. Un job encore en cours doit d'abord être arrêté.",
            annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = true,
                    idempotentHint = false, openWorldHint = false))
    SuppressionResult supprimer_job(@ToolArg(description = "Id du job") String jobId) {
        if (!solverJobService.forget(jobId)) {
            throw new NoSuchElementException("Job introuvable ou encore en cours : " + jobId);
        }
        return new SuppressionResult(jobId, true);
    }

    @Tool(description = "Détaille les contraintes de niveau HARD encore violées lors de la dernière analyse "
            + "(solve ou analyze), avec le message de chaque violation. Liste vide si la dernière analyse est "
            + "entièrement faisable, ou s'il n'y a jamais eu d'analyse.",
            annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
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

    /**
     * Score of the persisted plan, rule by rule. No violation message here —
     * those name animateurs and go out anonymised, through
     * {@code expliquer_echec_contraintes_dures}.
     */
    public record DiagnosticPlanView(String score, int hardScore, int postesNonPourvus,
            List<ContrainteScoreView> contraintes) {
    }

    public record ContrainteScoreView(String name, String score, int nombreCorrespondances) {
    }
}
