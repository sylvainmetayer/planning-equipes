import { ChangeDetectionStrategy, Component, DestroyRef, computed, effect, inject, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatDialog } from '@angular/material/dialog';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ApiService } from '../../core/api.service';
import { resumeEnvoi } from '../../core/envoi-planning';
import { intlLocale } from '../../core/locale';
import {
  ChangementAffectation,
  CompteRenduEnvoi,
  FeasibilityReport,
  JobView,
  PerimetreReplanification,
  PlanningDiagnostic,
  ResultatSolveIncremental,
  StatistiquesIncremental
} from '../../core/models';
import { PlanningResolutionStore } from '../../core/planning-resolution.store';
import { NotificationService } from '../../core/notification.service';
import { PlanningStateService } from '../../core/planning-state.service';
import { ProblemesStore } from '../../core/problemes.store';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService, extraireDiagnostic, formatDuration } from '../../core/solver-job.service';
import { SolverSettingsService } from '../../core/solver-settings.service';
import { ConfirmService } from '../../shared/confirm-dialog';
import { FeasibilityBanner, HardIssue } from '../../shared/feasibility-banner';
import { OutputPanel } from '../../shared/output-panel';
import { ProblemSummaryBanner } from '../../shared/problem-summary-banner';
import { StatusMessage } from '../../shared/status-message';
import { ReplanificationDialog } from './replanification-dialog';
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

/** Unit the solver page edits the solver duration in — always converted to/from seconds for the API. */
export type SolverDurationUnit = 'SECONDES' | 'MINUTES' | 'HEURES';

const SOLVER_DURATION_UNIT_FACTORS: Record<SolverDurationUnit, number> = {
  SECONDES: 1,
  MINUTES: 60,
  HEURES: 3600
};

/** Per-unit `<input type="number">` granularity: whole seconds, half-minutes, quarter-hours. */
const SOLVER_DURATION_UNIT_STEP: Record<SolverDurationUnit, number> = {
  SECONDES: 1,
  MINUTES: 0.5,
  HEURES: 0.25
};

function secondsToValue(seconds: number, unit: SolverDurationUnit): number {
  return seconds / SOLVER_DURATION_UNIT_FACTORS[unit];
}

function valueToSeconds(value: number, unit: SolverDurationUnit): number {
  return value * SOLVER_DURATION_UNIT_FACTORS[unit];
}

/** Picks the largest unit that represents `seconds` as a whole number, so e.g. 180s shows as "3 min", not "0.05 h". */
function bestUnitFor(seconds: number): SolverDurationUnit {
  if (seconds !== 0 && seconds % SOLVER_DURATION_UNIT_FACTORS.HEURES === 0) {
    return 'HEURES';
  }
  if (seconds % SOLVER_DURATION_UNIT_FACTORS.MINUTES === 0) {
    return 'MINUTES';
  }
  return 'SECONDES';
}

/**
 * Solver page: launches the background solve job, and exports the resulting
 * planning (PDF + ICS bundled in one ZIP). The server always analyzes the
 * solve result as part of the same job (see {@code SolverJobService.submitSolve}
 * on the backend), so there is no separate analyze action and no client-side
 * chaining to keep in sync. Seeding the database lives on the Data setup
 * page, emptying it on the Debug page.
 */
@Component({
  selector: 'app-solver-page',
  imports: [
    MatProgressBarModule,
    StatusMessage,
    FormsModule,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatTableModule,
    MatTooltipModule,
    FeasibilityBanner,
    ProblemSummaryBanner,
    OutputPanel
  ],
  templateUrl: './solver-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class SolverPage {
  protected readonly output = signal('');
  protected readonly feasibility = signal<FeasibilityReport | null>(null);
  protected readonly hardScore = signal<number | null>(null);
  protected readonly hardIssues = signal<HardIssue[]>([]);
  protected readonly exportBusy = signal(false);
  protected readonly envoiBusy = signal(false);
  protected readonly arretEnCours = signal(false);

  /**
   * Result of the last incremental re-solve (issue #86), cleared as soon as a
   * full solve replaces the whole plan: the diff would then describe a
   * planning that no longer exists.
   */
  protected readonly incrementalStats = signal<StatistiquesIncremental | null>(null);
  protected readonly incrementalChangements = signal<ChangementAffectation[]>([]);
  protected readonly columnsChangements = ['quand', 'stand', 'avant', 'apres'];

  protected readonly solverDurationLoading = signal(false);
  protected readonly solverDurationSaving = signal(false);
  protected readonly solverDurationError = signal('');

  /**
   * Editable value + unit for the duration persisted server-side as seconds
   * (`/api/parametres-solveur`), only sent back when the user clicks
   * "Enregistrer" — an unsaved value never silently applies.
   */
  protected readonly solverDurationUnit = signal<SolverDurationUnit>('MINUTES');
  protected readonly solverDurationValueDraft = signal(0);
  protected readonly solverDurationSecondsSaved = signal(0);
  protected readonly solverDurationSecondsDraft = computed(() =>
    valueToSeconds(this.solverDurationValueDraft(), this.solverDurationUnit())
  );
  protected readonly solverDurationDirty = computed(
    () => Math.round(this.solverDurationSecondsDraft()) !== this.solverDurationSecondsSaved()
  );
  protected readonly solverDurationStep = computed(() => SOLVER_DURATION_UNIT_STEP[this.solverDurationUnit()]);

  /** The server-side lock, not a local flag: it also covers other browsers. */
  protected readonly solverBusy = computed(() => this.jobs.solverBusy());

  /**
   * The narrower, per-edition lock — what the diffusion actions wait on. They
   * read the planning persisted for the edition on screen, so only a solve
   * writing to THAT edition can hand out a half-rewritten planning; a run on
   * another edition leaves this one exactly as it was saved. Same rule as the
   * data-entry screens (see `docs/editions.md`, §5).
   */
  protected readonly editingLocked = computed(() => this.jobs.editingLocked());

  /**
   * Solves planned behind the running one. Server-side and shared: one planned
   * from another browser shows up here too, and can be removed from here.
   */
  protected readonly file = computed(() => this.jobs.file());

  /**
   * The two solve buttons say what the click will actually do. While the
   * solver is busy they plan the run instead of being greyed out — the whole
   * point being to prepare the next edition without waiting in front of the
   * screen.
   */
  protected readonly libelleSolve = computed(() =>
    this.solverBusy()
      ? $localize`:@@solver.planifierSolve:Planifier la résolution`
      : $localize`:@@solver.solve:Résoudre avec Timefold`
  );
  protected readonly libelleIncremental = computed(() =>
    this.solverBusy()
      ? $localize`:@@solver.planifierIncremental:Planifier la replanification`
      : $localize`:@@solver.incremental:Replanifier (incrémental)`
  );

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

  /** Completion time of the most recent finished SOLVE or ANALYZE job, if any has ever run. */
  protected readonly lastRunAt = signal<string | null>(null);
  protected readonly formattedLastRun = computed(() => {
    const lastRunAt = this.lastRunAt();
    return lastRunAt ? new Date(lastRunAt).toLocaleString(intlLocale()) : '';
  });

  protected readonly resolution = inject(PlanningResolutionStore);
  /** True once reference data was edited after the last solve: its result may be stale. */
  protected readonly dataStale = computed(() => this.resolution.dataStale());

  /**
   * Aggregate of the pre-solve capacity causes and of the last analysis' still
   * violated rules, summarised in the banner at the top of the page and detailed
   * on the Problèmes page.
   */
  protected readonly problemes = inject(ProblemesStore);

  /**
   * Volumetry of the problem Timefold is about to explore, recomputed live as
   * `referenceData`'s signals change (a CRUD edit, a sample load, a CSV/SQL
   * import...). `animateurTotal`/`posteTotal`/`contrainteAdHocTotal` come from
   * `/api/planning/volumetrie`, built server-side the exact same way an actual
   * solve is (one poste per required seat, not per stand) so they never drift
   * from what the solver logs report. `créneauTotal` counts the
   * edition's slots — exactly what the solver consumes.
   */
  private readonly referenceData = inject(ReferenceDataStore);
  protected readonly animateurTotal = computed(() => this.referenceData.volumetrie().animateurCount);
  protected readonly posteTotal = computed(() => this.referenceData.volumetrie().posteCount);
  protected readonly contrainteAdHocTotal = computed(() => this.referenceData.volumetrie().contrainteAdHocCount);
  protected readonly creneauTotal = computed(() => this.referenceData.creneaux().length);

  /**
   * Timefold's own "approximate problem scale": log10 of the search space size,
   * i.e. `entityCount * log10(valueCount)` (valueCount ^ entityCount, not a
   * product of the counts above — a plain product would be off by thousands of
   * orders of magnitude and isn't worth displaying as a number).
   *
   * This is a naive upper bound: it counts every assignment, including the ones
   * no constraint would ever allow (an animateur on several postes of the same
   * créneau, or on a day they are not available). Narrowing it does not help —
   * one-poste-per-créneau exclusivity only removes ~44 orders of magnitude, and
   * even assuming 10 eligible animateurs per poste still leaves 10^2823. Hence
   * the wording in the template: "espace de recherche", not "combinaisons".
   */
  protected readonly ordreDeGrandeur = computed(() => {
    const animateurs = this.animateurTotal();
    const postes = this.posteTotal();
    return animateurs > 1 && postes > 0 ? Math.round(postes * Math.log10(animateurs)) : 0;
  });

  private readonly api = inject(ApiService);
  private readonly planningState = inject(PlanningStateService);
  private readonly jobs = inject(SolverJobService);
  private readonly solverSettings = inject(SolverSettingsService);
  private readonly notifications = inject(NotificationService);
  private readonly confirm = inject(ConfirmService);
  private readonly dialog = inject(MatDialog);
  private readonly crud = inject(ReferenceCrudService);

  constructor() {
    void this.loadLastRun();
    void this.problemes.reload();
    void this.loadSolverDuration();
    void this.crud.reload();
    // Results are pushed by the job service, whoever started the job: a solve
    // launched from another browser also lands here when it completes, already
    // analyzed.
    // Unregistered on destroy: this page is lazy-loaded and rebuilt on every
    // navigation, so keeping the handler would stack one more copy per visit.
    // Both kinds of solve land here: a full one carries a bare diagnostic, an
    // incremental one wraps it (issue #86). Registered separately because they
    // are two job types server-side, and unregistered together on destroy —
    // this page is lazy-loaded and rebuilt on every navigation, so a handler
    // left behind would stack one more copy per visit.
    const surResultatSolve = (result: unknown): void => {
      const diagnostic = extraireDiagnostic(result);
      if (diagnostic) {
        this.applySolveResult(diagnostic);
      }
      this.applyIncrementalResult(result);
      void this.loadLastRun();
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

  private async loadSolverDuration(): Promise<void> {
    this.solverDurationLoading.set(true);
    this.solverDurationError.set('');
    try {
      await this.solverSettings.refresh();
      const seconds = this.solverSettings.secondsLimit();
      this.applySolverDurationSeconds(seconds, bestUnitFor(seconds));
    } catch (error) {
      this.solverDurationError.set(errorPrefix(error));
    } finally {
      this.solverDurationLoading.set(false);
    }
  }

  protected onSolverDurationValueDraftChange(value: number): void {
    this.solverDurationValueDraft.set(value);
  }

  /** Switching unit re-expresses the current draft value, it never resets it (e.g. 3 min → 180 s, not back to 0). */
  protected onSolverDurationUnitChange(unit: SolverDurationUnit): void {
    const seconds = this.solverDurationSecondsDraft();
    this.solverDurationUnit.set(unit);
    this.solverDurationValueDraft.set(secondsToValue(seconds, unit));
  }

  protected async saveSolverDuration(): Promise<void> {
    this.solverDurationSaving.set(true);
    this.solverDurationError.set('');
    try {
      await this.solverSettings.setSecondsLimit(this.solverDurationSecondsDraft());
      this.applySolverDurationSeconds(this.solverSettings.secondsLimit(), this.solverDurationUnit());
      this.notifications.notify({
        title: $localize`:@@dataSetup.solverDuration.saved:Durée de résolution enregistrée`,
        message: $localize`:@@dataSetup.solverDuration.savedHint:Appliquée à tous les navigateurs.`,
        variant: 'success'
      });
    } catch (error) {
      this.solverDurationError.set(errorPrefix(error));
    } finally {
      this.solverDurationSaving.set(false);
    }
  }

  /** Syncs draft + saved state from a seconds value freshly read from (or written to) the server, in the given unit. */
  private applySolverDurationSeconds(seconds: number, unit: SolverDurationUnit): void {
    this.solverDurationUnit.set(unit);
    this.solverDurationValueDraft.set(secondsToValue(seconds, unit));
    this.solverDurationSecondsSaved.set(Math.round(seconds));
  }

  /**
   * Why the solver actions are greyed out, in words. A disabled button with no
   * explanation is the classic dead end: the user clicks, nothing happens, and
   * nothing says a run started from another browser is holding the lock.
   */
  /** Shared with the Contraintes screen: see `ProblemesStore.alerteReglesLegales`. */
  protected readonly alerteReglesLegales = computed(() => this.problemes.alerteReglesLegales());

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
    const perimetre = await firstValueFrom(
      this.dialog.open(ReplanificationDialog, { width: '640px' }).afterClosed()
    );
    if (!perimetre) {
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
      await this.jobs.submitSolveIncremental(perimetre as PerimetreReplanification, undefined, enFile);
      this.output.set(
        enFile
          ? $localize`:@@solver.incremental.planned:Replanification planifiée : elle démarrera d'elle-même sur cette édition dès que la tâche en cours sera terminée.`
          : $localize`:@@solver.incremental.submitted:Replanification incrémentale en cours : le planning enregistré sert de point de départ, seuls les postes rouverts sont recalculés.`
      );
    } catch (error) {
      this.output.set(errorPrefix(error));
    }
  }

  /** Fills — or clears — the "what moved" panel from a finished SOLVE result. */
  private applyIncrementalResult(result: unknown): void {
    const incremental = result as Partial<ResultatSolveIncremental> | null;
    if (incremental && incremental.statistiques && Array.isArray(incremental.changements)) {
      this.incrementalStats.set(incremental.statistiques);
      this.incrementalChangements.set(incremental.changements);
      return;
    }
    this.incrementalStats.set(null);
    this.incrementalChangements.set([]);
  }

  /** What a planned job will do, and to which edition — the two things worth reading in the queue. */
  protected typeFileLabel(job: JobView): string {
    return job.type === 'SOLVE_INCREMENTAL'
      ? $localize`:@@job.type.solveIncremental:Replanification incrémentale`
      : $localize`:@@job.type.solve:Résolution Timefold`;
  }

  protected editionFileLabel(job: JobView): string {
    return job.editionNom ?? job.editionId ?? '?';
  }

  /** An empty crew is a hole in the plan, and must read as one. */
  protected equipeLabel(equipe: string[]): string {
    return equipe.length === 0 ? $localize`:@@solver.incremental.personne:(personne)` : equipe.join(', ');
  }

  protected quandLabel(changement: ChangementAffectation): string {
    const jour = changement.date
      ? new Date(`${changement.date}T00:00:00`).toLocaleDateString(intlLocale(), {
          weekday: 'short',
          day: 'numeric',
          month: 'short'
        })
      : '';
    const heures = changement.heureDebut && changement.heureFin
      ? `${changement.heureDebut.slice(0, 5)} – ${changement.heureFin.slice(0, 5)}`
      : '';
    return [jour, heures].filter((part) => part.length > 0).join(' ');
  }

  protected async onTimefoldSolve(): Promise<void> {
    const enFile = this.jobs.solverBusy();
    this.output.set(
      enFile
        ? $localize`:@@solver.planning:Planification de la résolution...`
        : $localize`:@@solver.submitting:Envoi de la résolution au solveur en arrière-plan...`
    );
    this.feasibility.set(null);
    this.hardScore.set(null);
    this.hardIssues.set([]);
    try {
      // The problem is built server-side from the reference data: no planning is
      // uploaded, so even a very large scenario can be solved without hitting the
      // HTTP body limit (which would fail with a network error). A planned solve
      // builds it when it starts, not now — the edition can keep being prepared.
      await this.jobs.submitSolveFromReferenceData(this.solverSettings.secondsLimit(), enFile);
      this.output.set(
        enFile
          ? $localize`:@@solver.planned:Résolution planifiée : elle démarrera d'elle-même sur cette édition dès que la tâche en cours sera terminée. Vous pouvez fermer cet écran.`
          : $localize`:@@solver.submitted:Résolution avec Timefold sur le serveur, puis analyse automatique du résultat. Vous pouvez continuer à naviguer ; une notification apparaîtra à chaque étape, ici et dans tout autre navigateur observant ce serveur.`
      );
    } catch (error) {
      this.output.set(errorPrefix(error));
    }
  }

  /**
   * Removes a solve from the queue before it starts. Nothing ran, so there is
   * nothing to stop — unlike stopping the running job, which keeps its partial
   * result.
   */
  protected async onRetirerDeLaFile(job: JobView): Promise<void> {
    try {
      await this.jobs.retirerDeLaFile(job.id);
    } catch (error) {
      this.output.set(errorPrefix(error));
    }
  }

  /**
   * The organiser's own copy: one PDF holding every assignment, laid out by
   * day and by stand. A GET, unlike the per-animateur bundle below — the
   * server reads the persisted planning itself rather than having the browser
   * upload several megabytes of JSON just to get a document back.
   */
  protected async onExportGlobalPdf(): Promise<void> {
    this.exportBusy.set(true);
    this.output.set($localize`:@@solver.exportGlobalBuilding:Construction du PDF global...`);
    try {
      this.output.set(
        await this.api.downloadGet('/api/planning/export/pdf/global', 'planning-global.pdf', 'application/pdf')
      );
    } catch (error) {
      this.output.set(errorPrefix(error));
    } finally {
      this.exportBusy.set(false);
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

  /**
   * Mails every animateur holding at least one poste their individual
   * planning (PDF + espace link), built server-side from the persisted
   * planning — same read-only source as the global PDF above. Confirmed
   * first: it reaches everyone at once.
   */
  protected async onEnvoyerPlannings(): Promise<void> {
    if (this.envoiBusy()) {
      return;
    }
    const confirme = await this.confirm.ask({
      title: $localize`:@@solver.envoiTous.confirmTitre:Envoyer tous les plannings ?`,
      message: $localize`:@@solver.envoiTous.confirmMessage:Chaque animateur du planning enregistré ayant une adresse e-mail recevra son planning individuel en PDF, avec le lien vers son espace en ligne.`,
      confirmLabel: $localize`:@@solver.envoiTous.confirmAction:Envoyer`
    });
    if (!confirme) {
      return;
    }
    this.envoiBusy.set(true);
    this.output.set($localize`:@@solver.envoiTousEnCours:Envoi des plannings par e-mail...`);
    try {
      const compteRendu = await this.api.post<CompteRenduEnvoi>('/api/planning/envoi/tous', null);
      const resume = resumeEnvoi(compteRendu);
      this.output.set(resume.details ? `${resume.titre} — ${resume.details}` : resume.titre);
    } catch (error) {
      this.output.set(errorPrefix(error));
    } finally {
      this.envoiBusy.set(false);
    }
  }

  protected async onExportPlanning(): Promise<void> {
    this.exportBusy.set(true);
    this.output.set($localize`:@@solver.exportBuilding:Construction de l'archive d'export...`);
    try {
      const planning = await this.planningState.require();
      this.output.set(
        await this.api.downloadPost('/api/planning/export/bundle/all', 'planning.zip', planning, 'application/zip')
      );
    } catch (error) {
      this.output.set(errorPrefix(error));
    } finally {
      this.exportBusy.set(false);
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

