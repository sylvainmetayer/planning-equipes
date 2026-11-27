package dev.sylvain.planning.mcp;

import ai.timefold.solver.core.api.score.HardMediumSoftScore;
import com.fasterxml.jackson.annotation.JsonInclude;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.EditionContext;
import dev.sylvain.planning.service.analyse.PlanningDiagnosticService.ConstraintDiagnostic;
import dev.sylvain.planning.service.analyse.PlanningDiagnosticService.PlanningDiagnostic;
import dev.sylvain.planning.service.analyse.ScoreReading;
import dev.sylvain.planning.service.diagnostic.BlockerPlaybook;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.solve.ConstraintAnalysisStore;
import dev.sylvain.planning.service.solve.ConstraintAnalysisStore.StoredAnalysis;
import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import dev.sylvain.planning.service.solve.Reamorcage;
import dev.sylvain.planning.service.solve.ReplanificationScope;
import dev.sylvain.planning.service.solve.SolverJobService;
import dev.sylvain.planning.service.solve.SolverJobService.SolverBusyException;
import dev.sylvain.planning.service.solve.SolverJobService.SolverJob;
import dev.sylvain.planning.solver.ConstraintCatalog;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

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
@RefusMetier
@Journalise
@ApplicationScoped
public class SolveurMcpTools {

    private final SolverJobService solverJobService;

    private final PlanningPersistenceService persistenceService;

    private final ConstraintAnalysisStore analysisStore;

    private final ReferenceDataService referenceDataService;

    private final EditionContext editionContext;

    @Inject
    SolveurMcpTools(
            SolverJobService solverJobService,
            PlanningPersistenceService persistenceService,
            ConstraintAnalysisStore analysisStore,
            ReferenceDataService referenceDataService,
            EditionContext editionContext) {
        this.solverJobService = solverJobService;
        this.persistenceService = persistenceService;
        this.analysisStore = analysisStore;
        this.referenceDataService = referenceDataService;
        this.editionContext = editionContext;
    }

    /**
     * The job registry is global: an organiser reaching /mcp by OAuth (ADR 0097)
     * sees, stops and forgets the jobs of its editions only, as on the REST side.
     */
    private Optional<SolverJob> visible(Optional<SolverJob> job) {
        return job.filter(candidate -> editionContext.visibleToCaller(candidate.getEditionId()));
    }

    @Tool(
            name = "lancer_calcul",
            description = "Calculer : lance en tâche de fond le calcul complet de l'édition, depuis le plan en place "
                    + "s'il en existe un — les verrous et le passé figé sont respectés, tout le reste peut bouger. "
                    + "Renvoie l'id du calcul à suivre par consulter_calcul. deZero=true recommence de zéro et perd la "
                    + "qualité déjà atteinte : ne le demander qu'explicitement. Avec enFile, le calcul attend son tour "
                    + "au lieu d'être refusé quand le solveur est occupé. Pour ne reprendre que les sièges en écart, "
                    + "lancer_correction.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = true,
                            idempotentHint = false,
                            openWorldHint = false))
    JobMcpView startCalcul(
            @ToolArg(
                            description = "Durée max en secondes (défaut : le budget de l'instance, voir "
                                    + "consulter_parametres, section calcul) ; refusée au-dessus du plafond de "
                                    + "l'instance",
                            required = false)
                    Long secondes,
            @ToolArg(description = "Attendre son tour si le solveur est occupé, au lieu d'échouer", required = false)
                    Boolean enFile,
            @ToolArg(
                            description = "Recommencer de zéro au lieu de repartir du plan en place (défaut false)",
                            required = false)
                    Boolean deZero,
            @ToolArg(
                            description =
                                    "Où tourne le calcul quand l'instance a une machine de calcul : true sur elle, "
                                            + "false sur place ; par défaut, le réglage de l'instance. Refusé à true sans "
                                            + "machine de calcul configurée",
                            required = false)
                    Boolean distant,
            @ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        Reamorcage depart = Boolean.TRUE.equals(deZero) ? Reamorcage.AUCUN : Reamorcage.AUTO;
        return submit(() ->
                solverJobService.submitSolveFromReferenceData(secondes, Boolean.TRUE.equals(enFile), depart, distant));
    }

    @Tool(
            name = "lancer_correction",
            description =
                    "Corriger : recalcule les seuls sièges en écart du plan en place — ceux qu'un changement "
                            + "a invalidés (titulaire supprimé, devenu indisponible ce jour-là…) — et fige tout le reste pour "
                            + "ce calcul, d'où un budget bien plus court qu'un calcul complet (60 s par défaut). animateurIds, "
                            + "jours et standIds rouvrent en plus tous les sièges qu'ils désignent ; ce périmètre ne touche pas "
                            + "les verrous et ne vaut que pour ce calcul. Renvoie l'id du calcul à suivre par consulter_calcul.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = true,
                            idempotentHint = false,
                            openWorldHint = false))
    JobMcpView startCorrection(
            @ToolArg(description = "Ids d'animateurs dont tous les sièges sont rouverts", required = false)
                    List<String> animateurIds,
            @ToolArg(description = "Jours (AAAA-MM-JJ) dont tous les sièges sont rouverts", required = false)
                    List<String> jours,
            @ToolArg(description = "Ids de stands dont tous les sièges sont rouverts", required = false)
                    List<String> standIds,
            @ToolArg(
                            description = "Durée max en secondes (défaut 60) ; refusée au-dessus du plafond de "
                                    + "l'instance. Aucun arrêt sur plateau",
                            required = false)
                    Long secondes,
            @ToolArg(description = "Attendre son tour si le solveur est occupé, au lieu d'échouer", required = false)
                    Boolean enFile,
            @ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        return solveIncremental(animateurIds, jours, standIds, secondes, enFile, edition);
    }

    @Tool(
            name = "arreter_calcul",
            description = "Arrête un calcul en cours : le solveur garde la meilleure solution trouvée jusqu'ici. "
                    + "Sans id, arrête le calcul actif s'il y en a un.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    JobMcpView stopCalcul(@ToolArg(description = "Id du calcul à arrêter", required = false) String jobId) {
        return stopSolver(jobId);
    }

    @Tool(
            name = "consulter_calcul",
            description = "Où en est un calcul : celui donné par son id, sinon le calcul actif — status IDLE quand "
                    + "aucun ne tourne. Avec historique=true, rend aussi la liste des calculs de vos éditions, en cours "
                    + "et terminés.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    CalculView consultCalcul(
            @ToolArg(description = "Id du calcul à interroger", required = false) String jobId,
            @ToolArg(description = "Rendre aussi la liste des calculs (défaut false)", required = false)
                    Boolean historique) {
        return new CalculView(solverStatus(jobId), Boolean.TRUE.equals(historique) ? listJobs() : null);
    }

    @Tool(
            name = "supprimer_calcul",
            description =
                    "Supprime un calcul terminé de l'historique. Un calcul encore en cours doit d'abord être arrêté.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = true,
                            idempotentHint = false,
                            openWorldHint = false))
    SuppressionResult deleteCalcul(@ToolArg(description = "Id du calcul") String jobId) {
        return deleteJob(jobId);
    }

    @Deprecated
    @Tool(
            name = "lancer_solveur",
            description = "Ancien nom, gardé jusqu'en 5.0 : utiliser lancer_calcul (deZero pour reamorcage AUCUN). "
                    + "Lance une résolution en tâche de fond à partir des données de référence persistées "
                    + "(stands, créneaux, animateurs). Renvoie l'id du job à interroger via statut_solveur. "
                    + "Avec enFile, la résolution attend son tour au lieu d'être refusée quand le solveur est occupé. "
                    + "Par défaut elle repart du plan enregistré s'il en existe un, sans rien figer : un calcul de zéro "
                    + "perd la qualité déjà atteinte, demandez-le explicitement (reamorcage=AUCUN).",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = true,
                            idempotentHint = false,
                            openWorldHint = false))
    JobMcpView startSolver(
            @ToolArg(
                            description = "Durée max en secondes (défaut : le budget de l'instance, voir "
                                    + "consulter_parametres, section calcul) ; refusée au-dessus du plafond de "
                                    + "l'instance",
                            required = false)
                    Long secondes,
            @ToolArg(description = "Attendre son tour si le solveur est occupé, au lieu d'échouer", required = false)
                    Boolean enFile,
            @ToolArg(
                            description = "Point de départ : AUTO (défaut, repart du plan enregistré s'il existe), "
                                    + "PLAN_COURANT (échoue s'il n'y a pas de plan), AUCUN (calcul de zéro)",
                            required = false)
                    String reamorcage,
            @ToolArg(
                            description =
                                    "Où tourne le calcul quand l'instance a une machine de calcul : true sur elle, "
                                            + "false sur place ; par défaut, le réglage de l'instance. Refusé à true sans "
                                            + "machine de calcul configurée",
                            required = false)
                    Boolean distant,
            @ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        Reamorcage depart = Reamorcage.parse(reamorcage);
        return submit(() ->
                solverJobService.submitSolveFromReferenceData(secondes, Boolean.TRUE.equals(enFile), depart, distant));
    }

    @Deprecated
    @Tool(
            name = "resoudre_incremental",
            description = "Ancien nom, gardé jusqu'en 5.0 : utiliser lancer_correction. "
                    + "Relance une résolution partielle à partir du planning persisté : tout ce qu'un "
                    + "changement tardif n'a pas invalidé reste figé, seul le périmètre rouvert est recalculé — d'où un "
                    + "budget bien plus court qu'une résolution complète (60 s par défaut). Sans périmètre, seul ce que "
                    + "les changements ont invalidé est rouvert. Le périmètre ne touche pas les verrouillages : il ne "
                    + "vaut que pour ce job.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = true,
                            idempotentHint = false,
                            openWorldHint = false))
    JobMcpView solveIncremental(
            @ToolArg(description = "Ids d'animateurs dont tous les postes sont rouverts", required = false)
                    List<String> animateurIds,
            @ToolArg(description = "Jours (AAAA-MM-JJ) dont tous les postes sont rouverts", required = false)
                    List<String> jours,
            @ToolArg(description = "Ids de stands dont tous les postes sont rouverts", required = false)
                    List<String> standIds,
            @ToolArg(
                            description = "Durée max en secondes (défaut 60) ; refusée au-dessus du plafond de "
                                    + "l'instance. Aucun arrêt sur plateau",
                            required = false)
                    Long secondes,
            @ToolArg(description = "Attendre son tour si le solveur est occupé, au lieu d'échouer", required = false)
                    Boolean enFile,
            @ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        ReplanificationScope scope = new ReplanificationScope(
                animateurIds == null ? Set.of() : new LinkedHashSet<>(animateurIds),
                McpArgs.dates(jours, "jours"),
                standIds == null ? Set.of() : new LinkedHashSet<>(standIds));
        return submit(() -> solverJobService.submitSolveIncremental(secondes, scope, Boolean.TRUE.equals(enFile)));
    }

    /**
     * Turns the busy-solver refusal into a sentence naming the job in the way,
     * shared by both launches. The submission itself is a supplier so the
     * problem is built <b>inside</b> the job rather than here: that is what
     * makes a queued run solve the edition as it stands when its turn comes,
     * and what lets a restart replay it.
     */
    private JobMcpView submit(Supplier<SolverJob> submission) {
        try {
            return toView(submission.get());
        } catch (SolverBusyException e) {
            throw new BusinessError.Conflict("Solveur déjà occupé par le job "
                    + e.getActiveJob().getId() + " : relancez avec enFile pour attendre son tour.");
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
    @Computes
    @Tool(
            name = "diagnostiquer_plan",
            description = "Diagnostic du planning persisté : score global et score de chaque contrainte, nombre de "
                    + "correspondances, postes non pourvus, et la lecture du score en quelques phrases. Recalculé à la demande sur le plan en base, avec les "
                    + "contraintes et pondérations actives du moment — aucune résolution n'est lancée. Avec "
                    + "ecartsDurs=true, rend aussi le détail des règles dures non tenues : le message anonymisé de "
                    + "chaque écart et les actions types qui le règlent.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    DiagnosticPlanView diagnosePlan(
            @ToolArg(description = "Rendre aussi le détail des écarts durs (défaut false)", required = false)
                    Boolean ecartsDurs,
            @ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        StoredAnalysis analyse = analysisStore.refreshFromPersistedPlan();
        if (analyse == null) {
            throw new IllegalStateException("Aucun planning persisté : lancez d'abord une résolution.");
        }
        PlanningDiagnostic diagnostic = analyse.diagnostic();
        return new DiagnosticPlanView(
                diagnostic.score(),
                diagnostic.hardScore(),
                diagnostic.postesNonPourvus(),
                diagnostic.contraintes().stream()
                        .map(contrainte -> new ContrainteScoreView(
                                contrainte.name(),
                                contrainte.score(),
                                contrainte.matchCount(),
                                penalises(contrainte.score()) ? actions(contrainte) : List.of()))
                        .toList(),
                diagnostic.lecture().stream()
                        .map(ScoreReading.ScoreSentence::texte)
                        .toList(),
                Boolean.TRUE.equals(ecartsDurs) ? hardViolations(diagnostic) : null);
    }

    @Deprecated
    @Tool(
            name = "arreter_solveur",
            description = "Ancien nom, gardé jusqu'en 5.0 : utiliser arreter_calcul. "
                    + "Arrête le job en cours (le solveur renvoie sa meilleure solution trouvée jusqu'ici). "
                    + "Sans id, arrête le job actif s'il y en a un.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    JobMcpView stopSolver(@ToolArg(description = "Id du job à arrêter", required = false) String jobId) {
        String id = jobId != null
                ? jobId
                : visible(solverJobService.findActive())
                        .map(SolverJob::getId)
                        .orElseThrow(() -> new NoSuchElementException("Aucun solveur actif"));
        if (visible(solverJobService.find(id)).isEmpty()) {
            throw new BusinessError.NotFound("Job introuvable : " + id);
        }
        return solverJobService
                .cancel(id)
                .map(SolveurMcpTools::toView)
                .orElseThrow(() -> new NoSuchElementException("Job introuvable : " + id));
    }

    @Deprecated
    @Tool(
            name = "statut_solveur",
            description =
                    "Ancien nom, gardé jusqu'en 5.0 : utiliser consulter_calcul. "
                            + "Statut du job en cours, ou d'un job donné par son id. Sans id et sans job actif, répond status IDLE : aucun solveur ne tourne.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    JobMcpView solverStatus(@ToolArg(description = "Id du job à interroger", required = false) String jobId) {
        if (jobId != null) {
            return visible(solverJobService.find(jobId))
                    .map(SolveurMcpTools::toView)
                    .orElseThrow(() -> new BusinessError.NotFound("Job introuvable : " + jobId));
        }
        // Never null: the MCP transport cannot encode it, and answered
        // « Internal error » to the one question an assistant asks between
        // two solves.
        return solverJobService
                .findActive()
                .map(job ->
                        visible(Optional.of(job)).map(SolveurMcpTools::toView).orElse(OTHER_EDITION))
                .orElse(IDLE);
    }

    /** The answer of {@code statut_solveur} when nothing runs: no job, and a status no job ever carries. */
    static final JobMcpView IDLE = new JobMcpView(
            null,
            null,
            "IDLE",
            null,
            null,
            null,
            null,
            null,
            0L,
            null,
            null,
            null,
            "Aucun solveur ne tourne.",
            null,
            null);

    /** The answer of {@code statut_solveur} when the job running is of an edition the caller cannot see. */
    static final JobMcpView OTHER_EDITION = new JobMcpView(
            null,
            null,
            "OCCUPE",
            null,
            null,
            null,
            null,
            null,
            0L,
            null,
            null,
            null,
            "Le solveur calcule pour une autre édition.",
            null,
            null);

    @Deprecated
    @Tool(
            name = "lister_jobs",
            description = "Ancien nom, gardé jusqu'en 5.0 : utiliser consulter_calcul (historique). "
                    + "Liste tous les jobs de résolution/analyse (en cours et terminés).",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    List<JobMcpView> listJobs() {
        return solverJobService.list().stream()
                .filter(job -> editionContext.visibleToCaller(job.getEditionId()))
                .map(SolveurMcpTools::toView)
                .toList();
    }

    @Deprecated
    @Tool(
            name = "resultats_animateur",
            description =
                    "Ancien nom, gardé jusqu'en 5.0 : utiliser consulter_planning (vue affectations, animateurId). "
                            + "Résultats de planification (postes affectés) pour un animateur donné, à partir du dernier "
                            + "planning persisté en base. Ne renvoie que des ids de stand/créneau, jamais de données personnelles.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    List<AffectationView> animateurResults(
            @ToolArg(description = "Id de l'animateur") String animateurId,
            @ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        PlanningEvenement planning = persistenceService.loadPersistedPlanning();
        if (planning == null || planning.getPostes() == null) {
            return List.of();
        }
        return planning.getPostes().stream()
                .filter(poste -> poste.getAnimateur() != null
                        && animateurId.equals(poste.getAnimateur().getId()))
                .map(SolveurMcpTools::toView)
                .toList();
    }

    @Deprecated
    @Tool(
            name = "supprimer_job",
            description = "Ancien nom, gardé jusqu'en 5.0 : utiliser supprimer_calcul. "
                    + "Supprime un job terminé de l'historique. Un job encore en cours doit d'abord être arrêté.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = true,
                            idempotentHint = false,
                            openWorldHint = false))
    SuppressionResult deleteJob(@ToolArg(description = "Id du job") String jobId) {
        if (visible(solverJobService.find(jobId)).isEmpty()) {
            throw new BusinessError.NotFound("Job introuvable : " + jobId);
        }
        if (!solverJobService.forget(jobId)) {
            throw new NoSuchElementException("Job introuvable ou encore en cours : " + jobId);
        }
        return new SuppressionResult(jobId, true);
    }

    @Deprecated
    @Tool(
            name = "expliquer_echec_contraintes_dures",
            description = "Ancien nom, gardé jusqu'en 5.0 : utiliser diagnostiquer_plan (ecartsDurs). "
                    + "Détaille les contraintes de niveau HARD encore non tenues lors de la dernière analyse "
                    + "(solve ou analyze), avec le message de chaque écart et les actions types qui la "
                    + "règlent (code, geste, explication). Liste vide si la dernière analyse est "
                    + "entièrement faisable, ou s'il n'y a jamais eu d'analyse.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    List<ViolationHardView> explainHardContraintesFailure(
            @ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        StoredAnalysis analysis = analysisStore.latest();
        return analysis == null ? List.of() : hardViolations(analysis.diagnostic());
    }

    /** The hard rules still matched on {@code diagnostic}, their lines anonymised. */
    private List<ViolationHardView> hardViolations(PlanningDiagnostic planningDiagnostic) {
        AnonymisationViolations anonymisation = AnonymisationViolations.of(referenceDataService);
        return planningDiagnostic.contraintes().stream()
                .filter(diagnostic -> diagnostic.hard() && diagnostic.matchCount() > 0)
                .map(diagnostic -> new ViolationHardView(
                        diagnostic.name(),
                        diagnostic.matchCount(),
                        anonymisation.anonymiser(diagnostic.violations()),
                        actions(diagnostic)))
                .toList();
    }

    /**
     * Whether a rule's score costs anything on some level. A reward — the
     * affinity of two animateurs, matched every time they share a stand — is
     * not a problem, and gets no action however many times it matched.
     */
    static boolean penalises(String score) {
        if (score == null || score.isBlank()) {
            return false;
        }
        try {
            HardMediumSoftScore parsed = HardMediumSoftScore.parseScore(score);
            return parsed.hardScore() < 0 || parsed.mediumScore() < 0 || parsed.softScore() < 0;
        } catch (IllegalArgumentException _) {
            return false;
        }
    }

    /**
     * The playbook of a rule in default, as an assistant reads it: the code,
     * the gesture and why — never the route, since an assistant has no screen
     * to send anyone to. The same actions the Diagnostic offers, so an
     * assistant proposes the same gesture.
     */
    private static List<ActionMcpView> actions(ConstraintDiagnostic diagnostic) {
        ConstraintCatalog.ConstraintDefinition definition = ConstraintCatalog.PAR_NOM.get(diagnostic.name());
        if (definition == null) {
            return List.of();
        }
        return BlockerPlaybook.forRule(
                        definition,
                        diagnostic.plancher() == null
                                ? null
                                : diagnostic.plancher().lien(),
                        diagnostic.position())
                .stream()
                .map(action -> new ActionMcpView(action.code(), action.libelle(), action.explication()))
                .toList();
    }

    private static JobMcpView toView(SolverJob job) {
        return new JobMcpView(
                job.getId(),
                job.getType().name(),
                job.getStatus().name(),
                job.getSecondsLimit(),
                job.getPlateauSeconds(),
                job.getSubmittedAt(),
                job.getStartedAt(),
                job.getFinishedAt(),
                job.getElapsedSeconds(),
                job.getFeasibleAtSeconds(),
                job.getRemoteStage(),
                job.getError(),
                avertissement(job.getResult()),
                job.getEditionId(),
                job.getEditionNom());
    }

    /**
     * « N sièges passés sont restés vides » (ADR 0044): the past seats the
     * persisted plan gave nobody — never charged, so the zero hard of a solve
     * during the event would otherwise hide them. {@code null} when there is
     * nothing to warn about, on a job without a result included.
     */
    private static String avertissement(Object result) {
        int vides =
                switch (result) {
                    case SolverJobService.ResultatSolve solve ->
                        solve.reamorcage() == null ? 0 : solve.reamorcage().postesPassesVides();
                    case SolverJobService.ResultatSolveIncremental incremental ->
                        incremental.statistiques().postesPassesVides();
                    case null, default -> 0;
                };
        return vides > 0 ? vides + " sièges passés sont restés vides." : null;
    }

    private static AffectationView toView(PosteAffectation poste) {
        return new AffectationView(
                poste.getId(),
                poste.getStand() == null ? null : poste.getStand().getId(),
                poste.getStand() == null ? null : poste.getStand().getNom(),
                poste.getCreneau() == null ? null : poste.getCreneau().getId(),
                poste.getCreneau() == null ? null : poste.getCreneau().getDate(),
                poste.getHeureDebutEffective(),
                poste.getHeureFinEffective());
    }

    /**
     * Statut/résultat d'un job, sans le corps volumineux du planning résolu (voir resultats_animateur pour ça).
     *
     * <p>The edition is part of the view because the job registry is global
     * while jobs are not: without it, {@code lister_jobs} would show two
     * editions' runs as one undifferentiated history (issue #181).</p>
     */
    public record JobMcpView(
            String id,
            String type,
            String status,
            Long secondsLimit,
            /** The feasible-plateau bailout the job runs under, 0 for none. */
            Long plateauSeconds,
            Instant submittedAt,
            Instant startedAt,
            Instant finishedAt,
            long elapsedSeconds,
            /** Seconds into the run at which the best plan first became feasible; null until it does. */
            Long feasibleAtSeconds,
            /** Where a solve run on the instance's worker stands (PREPARING, SOLVING); null for one run here. */
            String calculDistant,
            String error,
            String avertissement,
            String editionId,
            String editionNom) {}

    public record AffectationView(
            String posteId,
            String standId,
            String standNom,
            Long creneauId,
            LocalDate date,
            LocalTime heureDebut,
            LocalTime heureFin) {}

    /**
     * @param actions what to do about the rule, most likely gesture first —
     *                see {@code BlockerPlaybook}
     */
    public record ViolationHardView(
            String contrainte, int nombreCorrespondances, List<String> violations, List<ActionMcpView> actions) {}

    /** One action of the playbook: its stable code, the gesture, and why. */
    public record ActionMcpView(String code, String libelle, String explication) {}

    /**
     * Score of the persisted plan, rule by rule. No violation message here —
     * those name animateurs and go out anonymised, through
     * {@code expliquer_echec_contraintes_dures}.
     *
     * <p>{@code lecture} is the reading of the score the screens show, sentence
     * by sentence: rules, days and counts, never a person, so it goes out as
     * it is.</p>
     */
    public record DiagnosticPlanView(
            String score,
            int hardScore,
            int postesNonPourvus,
            List<ContrainteScoreView> contraintes,
            List<String> lecture,
            /** The hard rules still broken, only when asked (ecartsDurs); left out otherwise. */
            @JsonInclude(JsonInclude.Include.NON_NULL) List<ViolationHardView> ecartsDurs) {}

    /**
     * A solve job and, when asked, the history of the caller's editions.
     *
     * @param historique {@code null} — left out — unless asked
     */
    public record CalculView(
            JobMcpView calcul,
            @JsonInclude(JsonInclude.Include.NON_NULL) List<JobMcpView> historique) {}

    /**
     * @param actions what to do about the rule, only when it matched on this
     *                plan — see {@code BlockerPlaybook}
     */
    public record ContrainteScoreView(
            String name, String score, int nombreCorrespondances, List<ActionMcpView> actions) {}
}
