import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  signal,
  untracked,
  viewChild
} from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatDialog } from '@angular/material/dialog';
import { MatTooltipModule } from '@angular/material/tooltip';
import { PlanningApi } from '../../core/api/planning-api';
import { intlLocale } from '../../core/locale';
import {
  ChangementAffectation,
  FeasibilityReport,
  ImpactPublication,
  PerimetreReplanification,
  PlanningDiagnostic,
  PreviousPlan,
  Reamorcage,
  ReamorcageEffectue,
  ResultatSolveIncremental,
  StatistiquesIncremental
} from '../../core/models';
import { PlanningResolutionStore } from '../../core/planning-resolution.store';
import { PlanningStateService } from '../../core/planning-state.service';
import { ProblemesStore } from '../../core/problemes.store';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import {
  JobResults,
  SolverJobService,
  extraireDiagnostic,
  extraireImpactPublication,
  extrairePlanPrecedent,
  extraireReamorcage,
  formatDuration
} from '../../core/solver-job.service';
import { SolverSettingsService } from '../../core/solver-settings.service';
import { ConfirmService } from '../../shared/confirm-dialog';
import { FeasibilityBanner, HardIssue } from '../../shared/feasibility-banner';
import { OutputPanel } from '../../shared/output-panel';
import { ProblemSummaryBanner } from '../../shared/problem-summary-banner';
import { StatusMessage } from '../../shared/status-message';
import { IncrementalResult } from './incremental-result';
import { PublicationPanel } from './publication-panel';
import { ReplanificationDialog } from './replanification-dialog';
import { ScoreCurveCard } from './score-curve-card';
import { SolveRecap } from './solve-recap';
import { SolverDurationCard } from './solver-duration-card';
import { SolverQueue } from './solver-queue';
import { SolverVolumetry } from './solver-volumetry';
import { errorPrefix } from '../../core/error-message';

/**
 * A constraint's raw score string looks like `-14hard/0medium/0soft`
 * ({@link https://timefold.ai HardMediumSoftScore#toString}); extracts the
 * leading hard component so still-violated hard rules can be picked out of a
 * {@link PlanningDiagnostic.contraintes} list, which (unlike `ConstraintView`
 * on the Contraintes page) doesn't carry the constraint's `niveau`.
 */
function hardPart(score: string): number {
  const match = /^(-?\d+)hard/.exec(score);
  return match ? Number(match[1]) : 0;
}

/**
 * Solver page: launches the background solve job and receives its result,
 * whoever started it. The server always analyzes the solve result as part of
 * the same job (see {@code SolverJobService.submitSolve} on the backend), so
 * there is no separate analyze action and no client-side chaining to keep in
 * sync. Seeding the database lives on the Data setup page, emptying it on the
 * Debug page.
 *
 * <p>An orchestrator: the budget, the queue, the score curve, the diffusion,
 * the volumetry, the recap and the incremental diff are each a component of
 * their own next door. What stays here is what the result handler has to
 * write, and the three ways of launching a solve.</p>
 */
@Component({
  selector: 'app-solver-page',
  imports: [
    MatProgressBarModule,
    StatusMessage,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatTooltipModule,
    FeasibilityBanner,
    ProblemSummaryBanner,
    OutputPanel,
    SolverDurationCard,
    SolverQueue,
    ScoreCurveCard,
    PublicationPanel,
    SolverVolumetry,
    IncrementalResult,
    SolveRecap
  ],
  templateUrl: './solver-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class SolverPage {
  protected readonly output = signal('');
  protected readonly feasibility = signal<FeasibilityReport | null>(null);
  protected readonly hardScore = signal<number | null>(null);
  /** Full score of the last solve, the half of the comparison of issue #274 that this page produced. */
  protected readonly score = signal<string | null>(null);
  protected readonly hardIssues = signal<HardIssue[]>([]);
  /** Raised by the diffusion panel while it builds a document; named as the lock reason below. */
  protected readonly exportBusy = signal(false);
  protected readonly arretEnCours = signal(false);

  /** The diffusion panel owns the publication preview; the page asks for a re-read after a solve. */
  private readonly publication = viewChild.required(PublicationPanel);

  /**
   * Result of the last incremental re-solve (issue #86), cleared as soon as a
   * full solve replaces the whole plan: the diff would then describe a
   * planning that no longer exists.
   */
  protected readonly incrementalStats = signal<StatistiquesIncremental | null>(null);
  protected readonly incrementalChangements = signal<ChangementAffectation[]>([]);

  /**
   * What the last solve replaced (issue #274). A solve announcing only its own
   * score let an operator re-solve a good plan, read "0 hard" and leave with a
   * worse planning without ever being told; this is what makes the trade
   * legible. Null when there is nothing to compare against — first solve of an
   * edition, or a score that could not be established.
   */
  protected readonly planPrecedent = signal<PreviousPlan | null>(null);

  /** The server-side lock, not a local flag: it also covers other browsers. */
  protected readonly solverBusy = computed(() => this.jobs.solverBusy());

  /**
   * The narrower, per-edition lock — what the diffusion actions wait on. See
   * `docs/decisions/0001-cloisonnement-par-edition.md`, §5.
   */
  protected readonly editingLocked = inject(SolverJobService).editingLocked;

  /**
   * The two solve buttons say what the click will actually do. While the
   * solver is busy they plan the run instead of being greyed out — the whole
   * point being to prepare the next edition without waiting in front of the
   * screen.
   */
  protected readonly libelleSolve = computed(() =>
    this.solverBusy()
      ? $localize`:@@solver.planifierSolve:Planifier le calcul`
      : $localize`:@@solver.solve:Calculer le planning`
  );
  protected readonly libelleIncremental = computed(() =>
    this.solverBusy()
      ? $localize`:@@solver.planifierIncremental:Planifier la correction`
      : $localize`:@@solver.incremental:Corriger après un changement`
  );
  protected readonly libelleAFroid = computed(() =>
    this.solverBusy()
      ? $localize`:@@solver.planifierAFroid:Planifier un calcul de zéro`
      : $localize`:@@solver.aFroid:Recommencer de zéro`
  );

  /* ------------------------------ Point de départ ----------------------------- */

  /**
   * Seats the persisted plan holds — what « Calculer le planning » will start
   * from (issue #174). Read from the server, not derived from the last job of
   * this browser: a plan solved from another tab, restored from a snapshot, or
   * imported is a starting point too.
   */
  protected readonly affectationsEnregistrees = signal<number | null>(null);

  /** True when a plan exists to start from: what makes « Recommencer de zéro » a choice at all. */
  protected readonly planEnregistre = computed(() => (this.affectationsEnregistrees() ?? 0) > 0);

  /** Under the buttons: where the next calculation starts, said before the click rather than after. */
  protected readonly pointDeDepart = computed(() => {
    const affectations = this.affectationsEnregistrees();
    if (affectations === null) {
      return '';
    }
    if (affectations === 0) {
      return $localize`:@@solver.depart.aucun:Aucun plan enregistré : le calcul part de zéro.`;
    }
    const resoluLe = this.resolution.resolution()?.resoluLe;
    const quand = resoluLe ? new Date(resoluLe).toLocaleString(intlLocale()) : '';
    return quand
      ? $localize`:@@solver.depart.planDate:« Calculer le planning » repart du plan enregistré le ${quand}:date: (${affectations}:count: affectations) et cherche à l'améliorer ; rien n'est figé hormis les verrouillages.`
      : $localize`:@@solver.depart.plan:« Calculer le planning » repart du plan enregistré (${affectations}:count: affectations) et cherche à l'améliorer ; rien n'est figé hormis les verrouillages.`;
  });

  /** Where the last finished full solve started from, for the recap. */
  protected readonly reamorcageEffectue = signal<ReamorcageEffectue | null>(null);

  /** Whom the last finished solve would disturb, against the published plan. */
  protected readonly impactPublication = signal<ImpactPublication | null>(null);

  /**
   * "Fin estimée" of the run in progress: its start time plus the duration it
   * was submitted with. An upper bound — the solver stops earlier when its
   * unimproved-seconds budget runs out, and anyone can stop it by hand.
   * Empty when nothing runs, or when the server reported no limit for it.
   */
  protected readonly estimatedEnd = computed(() => {
    const end = this.jobs.estimatedEndMs();
    return end === null ? '' : new Date(end).toLocaleTimeString(intlLocale());
  });
  protected readonly estimatedRemaining = computed(() => {
    const remaining = this.jobs.remainingSeconds();
    return remaining === null ? '' : formatDuration(remaining);
  });

  /** Completion time of the most recent finished solve job, if any has ever run. */
  protected readonly lastRunAt = signal<string | null>(null);

  protected readonly resolution = inject(PlanningResolutionStore);
  /** True once reference data was edited after the last solve: its result may be stale. */
  protected readonly dataStale = computed(() => this.resolution.dataStale());

  /**
   * Aggregate of the pre-solve capacity causes and of the last analysis' still
   * violated rules, summarised in the banner at the top of the page and detailed
   * on the Problèmes page.
   */
  protected readonly problemes = inject(ProblemesStore);

  private readonly planningApi = inject(PlanningApi);
  private readonly planningState = inject(PlanningStateService);
  private readonly jobs = inject(SolverJobService);
  private readonly solverSettings = inject(SolverSettingsService);
  private readonly confirm = inject(ConfirmService);
  private readonly dialog = inject(MatDialog);
  private readonly crud = inject(ReferenceCrudService);

  constructor() {
    void this.loadLastRun();
    void this.chargerPointDeDepart();
    void this.problemes.reload();
    void this.crud.reload();
    // Results are pushed by the job service, whoever started the job: a solve
    // launched from another browser also lands here when it completes, already
    // analyzed. Both kinds of solve land here: a full one carries a bare
    // diagnostic, an incremental one wraps it (issue #86). Registered
    // separately because they are two job types server-side, and unregistered
    // together on destroy — this page is lazy-loaded and rebuilt on every
    // navigation, so a handler left behind would stack one more copy per visit.
    const surResultatSolve = (result: JobResults['SOLVE'] | null): void => {
      const diagnostic = extraireDiagnostic(result);
      if (diagnostic) {
        this.applySolveResult(diagnostic);
      }
      this.applyIncrementalResult(result);
      this.planPrecedent.set(extrairePlanPrecedent(result));
      this.reamorcageEffectue.set(extraireReamorcage(result));
      this.impactPublication.set(extraireImpactPublication(result));
      void this.loadLastRun();
      void this.chargerPointDeDepart();
      // The solve just rewrote the plan: the count of people to inform is no
      // longer the one from before.
      void this.publication().reloadPreview();
      // The solve rewrote both problem sources server-side (fresh feasibility
      // input and a new constraint analysis): re-read them for the summary.
      void this.problemes.reload();
    };
    const desabonner = [
      this.jobs.onResult('SOLVE', surResultatSolve),
      this.jobs.onResult('SOLVE_INCREMENTAL', surResultatSolve)
    ];
    inject(DestroyRef).onDestroy(() => desabonner.forEach((retirer) => retirer()));
    // Explains why the solver buttons are locked when the job comes from
    // somewhere else (another tab, another browser, a private window).
    effect(() => {
      const job = this.jobs.activeJob();
      if (job && !job.mine) {
        const description = untracked(() => this.jobs.activeJobDescription());
        this.output.set(
          $localize`:@@solver.lockedByOther:${description}:description: Les actions du solveur sont verrouillées jusqu'à la fin.`
        );
      }
    });
  }

  /** Shared with the Contraintes screen: see `ProblemesStore.alerteReglesLegales`. */
  protected readonly alerteReglesLegales = computed(() => this.problemes.alerteReglesLegales());

  /**
   * Why the solver actions are greyed out, in words. A disabled button with no
   * explanation is the classic dead end: the user clicks, nothing happens, and
   * nothing says a run started from another browser is holding the lock.
   */
  protected readonly raisonVerrou = computed(() => {
    if (this.exportBusy()) {
      return $localize`:@@solver.locked.export:Un export est en cours de génération.`;
    }
    if (!this.solverBusy()) {
      return '';
    }
    return this.jobs.activeJob()
      ? this.jobs.activeJobDescription()
      : $localize`:@@solver.locked.unknown:L'état du solveur n'est pas encore connu : les actions se débloquent dès la première réponse du serveur.`;
  });

  /**
   * Progress of the running job, as a share of the budget it was given —
   * derived from the estimated end rather than from `secondsLimit` alone.
   */
  protected readonly progression = computed(() => {
    const job = this.jobs.activeJob();
    const end = this.jobs.estimatedEndMs();
    const restant = this.jobs.remainingSeconds();
    if (!job || end === null || restant === null) {
      return null;
    }
    const total = Math.max(1, Math.round((end - job.startedAtMs) / 1000));
    return Math.min(100, Math.max(0, Math.round(((total - restant) / total) * 100)));
  });

  /**
   * Incremental re-solve (issue #86): the server restarts from the persisted
   * plan, pins whatever a late change did not invalidate and the perimeter
   * does not re-open, then re-fills only the rest — on a short budget. The
   * result says exactly which crews moved.
   */
  protected async onSolveIncremental(): Promise<void> {
    const scope = await firstValueFrom(
      this.dialog.open(ReplanificationDialog, { width: '640px' }).afterClosed()
    );
    if (!scope) {
      return;
    }
    // Busy solver: plan it instead of refusing. Read once, before the await, so
    // the message and the request agree even if the solver frees up meanwhile
    // (the server then simply starts it at once).
    const enFile = this.jobs.solverBusy();
    this.output.set(
      enFile
        ? $localize`:@@solver.incremental.planning:Planification de la replanification incrémentale...`
        : $localize`:@@solver.incremental.submitting:Envoi de la replanification incrémentale au solveur...`
    );
    try {
      // No duration passed on purpose: the server applies its own short budget,
      // an order of magnitude under the full-solve one.
      await this.jobs.submitSolveIncremental(scope as PerimetreReplanification, undefined, enFile);
      this.output.set(
        enFile
          ? $localize`:@@solver.incremental.planned:Replanification planifiée : elle démarrera d'elle-même sur cette édition dès que la tâche en cours sera terminée.`
          : $localize`:@@solver.incremental.submitted:Replanification incrémentale en cours : le planning enregistré sert de point de départ, seuls les postes rouverts sont recalculés.`
      );
    } catch (error) {
      this.output.set(errorPrefix(error));
    }
  }

  /**
   * Fills — or clears — the "what moved" panel from a finished SOLVE result.
   * A full solve answers a bare diagnostic, which carries neither field: the
   * union is narrowed by looking for them rather than by the job type, because
   * both types arrive through the same handler.
   */
  private applyIncrementalResult(result: JobResults['SOLVE'] | null): void {
    const incremental = result as Partial<ResultatSolveIncremental> | null;
    if (incremental && incremental.statistiques && Array.isArray(incremental.changements)) {
      this.incrementalStats.set(incremental.statistiques);
      this.incrementalChangements.set(incremental.changements);
      return;
    }
    this.incrementalStats.set(null);
    this.incrementalChangements.set([]);
  }

  /** The recap put the previous plan back: the comparison described a plan that is no longer the persisted one. */
  protected onPlanPrecedentRestaure(message: string): void {
    this.planPrecedent.set(null);
    this.output.set(message);
    void this.publication().reloadPreview();
  }

  /**
   * « Recommencer de zéro » (issue #174): the one gesture that can lose the
   * plan already reached, so it says so and asks. Not offered at all without
   * a plan — « Calculer le planning » already starts cold then.
   */
  protected async onRecommencerDeZero(): Promise<void> {
    const affectations = this.affectationsEnregistrees() ?? 0;
    const publishedAt = this.publication().preview()?.dernierePublicationLe;
    const message = $localize`:@@solver.aFroid.confirm.message:Le plan enregistré (${affectations}:count: affectations) ne servira pas de point de départ : le calcul repart de rien et peut finir en dessous de lui. Pour l'améliorer plutôt que le remplacer, utilisez « Calculer le planning ».`;
    const avertissement = publishedAt
      ? ' ' +
        $localize`:@@solver.aFroid.confirm.publie:Un planning a été publié le ${new Date(publishedAt).toLocaleString(intlLocale())}:date: : repartir de zéro peut bousculer beaucoup de personnes déjà prévenues, là où « Calculer le planning » ne bouge que ce qui en vaut la peine.`
      : '';
    const confirme = await this.confirm.ask({
      title: $localize`:@@solver.aFroid.confirm.title:Recommencer de zéro ?`,
      message: message + avertissement,
      confirmLabel: $localize`:@@solver.aFroid.confirm.action:Recommencer de zéro`,
      danger: true
    });
    if (confirme) {
      await this.onTimefoldSolve('AUCUN');
    }
  }

  protected async onTimefoldSolve(reamorcage: Reamorcage = 'AUTO'): Promise<void> {
    const enFile = this.jobs.solverBusy();
    this.output.set(
      enFile
        ? $localize`:@@solver.planning:Planification du calcul...`
        : $localize`:@@solver.submitting:Envoi du calcul au solveur en arrière-plan...`
    );
    this.feasibility.set(null);
    this.hardScore.set(null);
    this.hardIssues.set([]);
    try {
      // The problem is built server-side from the reference data: no planning is
      // uploaded, so even a very large scenario can be solved without hitting the
      // HTTP body limit (which would fail with a network error). A planned solve
      // builds it when it starts, not now — the edition can keep being prepared.
      await this.jobs.submitSolveFromReferenceData(this.solverSettings.secondsLimit(), enFile, reamorcage);
      this.output.set(
        enFile
          ? $localize`:@@solver.planned:Calcul planifié : il démarrera de lui-même sur cette édition dès que la tâche en cours sera terminée. Vous pouvez fermer cet écran.`
          : $localize`:@@solver.submitted:Calcul du planning sur le serveur, puis analyse automatique du résultat. Vous pouvez continuer à naviguer ; une notification apparaîtra à chaque étape, ici et dans tout autre navigateur observant ce serveur.`
      );
    } catch (error) {
      this.output.set(errorPrefix(error));
    }
  }

  /**
   * Stops the running solver job, whoever started it — the partial result is
   * still analysed and persisted. Replaces the former toolbar-wide monitor:
   * this page already shows the progress, the button now lives next to it.
   */
  protected async onArreterSolveur(): Promise<void> {
    const job = this.jobs.activeJob();
    if (!job || this.arretEnCours()) {
      return;
    }
    const confirme = await this.confirm.ask({
      title: $localize`:@@solver.arreter:Arrêter le solveur`,
      message: $localize`:@@solver.arreterConfirm:Arrêter ${job.label}:jobLabel: en cours ? Le résultat partiel sera tout de même analysé et enregistré.`,
      confirmLabel: $localize`:@@solver.arreter:Arrêter le solveur`,
      danger: true
    });
    if (!confirme) {
      return;
    }
    this.arretEnCours.set(true);
    try {
      await this.jobs.cancel(job.id);
    } catch (error) {
      this.output.set(errorPrefix(error));
    } finally {
      this.arretEnCours.set(false);
    }
  }

  /** Best-effort like {@link loadLastRun}: without it the line under the buttons simply stays empty. */
  private async chargerPointDeDepart(): Promise<void> {
    try {
      const statut: { assignments?: number } = await this.planningApi.persistedCount();
      this.affectationsEnregistrees.set(typeof statut.assignments === 'number' ? statut.assignments : null);
      await this.resolution.reload();
    } catch {
      this.affectationsEnregistrees.set(null);
    }
  }

  private async loadLastRun(): Promise<void> {
    try {
      const jobs = await this.jobs.listJobs();
      // listJobs() is submitted-desc and jobs never overlap (single solver
      // lock), so the first entry with a finishedAt is the most recent run.
      this.lastRunAt.set(jobs.find((job) => job.finishedAt)?.finishedAt ?? null);
    } catch {
      this.lastRunAt.set(null); // best-effort: the page still works without history
    }
  }

  private applySolveResult(diagnostic: PlanningDiagnostic): void {
    // The solved planning is not part of the job result (it can be dozens of
    // MB); dedicated screens (calendars, exports) load it lazily from
    // /api/planning/persisted instead. Dropping the cached planning here makes
    // sure they pick up the freshly solved one rather than a stale in-memory copy.
    this.planningState.set(null);
    this.feasibility.set(diagnostic.faisabilite);
    this.hardScore.set(diagnostic.hardScore);
    this.score.set(diagnostic.score);
    const hardIssues = diagnostic.contraintes
      .filter((constraint) => hardPart(constraint.score) < 0)
      .map((constraint) => ({ name: constraint.name, matchCount: constraint.matchCount }));
    this.hardIssues.set(hardIssues);
    this.output.set(JSON.stringify(diagnostic, null, 2));
    // SolverJobService.reportFinishedJob already raised the feasibility
    // notification (it must run whether or not this page is mounted); this
    // only updates the on-page state.
  }
}
