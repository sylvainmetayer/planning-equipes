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
import { intlLocale } from '../../core/locale';
import {
  libelleDernierePublication,
  libellePublier,
  raisonIndisponible,
  resumePublication
} from '../../core/publication';
import {
  ApercuPublication,
  ChangementAffectation,
  FeasibilityReport,
  ImpactPublication,
  JobView,
  PerimetreReplanification,
  PlanningDiagnostic,
  PreviousPlan,
  RapportPublication,
  Reamorcage,
  ReamorcageEffectue,
  ResultatSolveIncremental,
  StatistiquesIncremental
} from '../../core/models';
import {
  defaultPanelStorage,
  readPanelCollapsed,
  writePanelCollapsed
} from '../../core/panel-collapse';
import { PlanSnapshotStore } from '../../core/plan-snapshot.store';
import { PlanningResolutionStore } from '../../core/planning-resolution.store';
import { NotificationService } from '../../core/notification.service';
import { PlanningStateService } from '../../core/planning-state.service';
import { ProblemesStore } from '../../core/problemes.store';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
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
import { ReplanificationDialog } from './replanification-dialog';
import { ScoreChart } from './score-chart';
import { errorPrefix } from '../../core/error-message';
import {
  SOLVER_DURATION_UNIT_STEP,
  SolverDurationUnit,
  bestUnitFor,
  secondsToValue,
  valueToSeconds
} from './solver-duration';

/**
 * A constraint's raw score string looks like `-14hard/0medium/0soft`
 * ({@link https://timefold.ai HardMediumSoftScore#toString}); extracts the
 * leading hard component so still-violated hard rules can be picked out of a
 * {@link PlanningDiagnostic.contraintes} list, which (unlike `ConstraintView`
 * on the Contraintes page) doesn't carry the constraint's `niveau`.
 */
/**
 * Where the folded state of the score curve is kept. A stable, namespaced key,
 * like every other one this application writes to localStorage.
 */
const SCORE_CURVE_STORAGE_KEY = 'planning-equipes.solver.scoreCurveCollapsed';

function hardPart(score: string): number {
  const match = /^(-?\d+)hard/.exec(score);
  return match ? Number(match[1]) : 0;
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
    OutputPanel,
    ScoreChart
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
  protected readonly exportBusy = signal(false);
  protected readonly arretEnCours = signal(false);

  /**
   * Publication state (issue #245), read on demand — when the screen opens and
   * after each publication. Nothing polls: the count is pending work to look
   * at, not an alarm to be pushed.
   */
  protected readonly publicationApercu = signal<ApercuPublication | null>(null);
  protected readonly publicationBusy = signal(false);
  /**
   * How many people the send under way concerns, frozen when it starts: the
   * preview reloads at the end and would otherwise drop to zero mid-sentence.
   * Real information rather than a fabricated percentage — the server answers
   * once, when everything has gone out.
   */
  protected readonly publicationDestinatairesEnCours = signal(0);
  protected readonly publicationListeOuverte = signal(false);
  protected readonly publicationLibelle = computed(() => libellePublier(this.publicationApercu()));
  protected readonly publicationRaisonIndisponible = computed(() =>
    raisonIndisponible(this.publicationApercu())
  );
  protected readonly publicationDerniere = computed(() =>
    libelleDernierePublication(this.publicationApercu(), intlLocale())
  );
  protected readonly publicationPossible = computed(() => {
    const apercu = this.publicationApercu();
    return !!apercu && apercu.nombreConcernes > 0 && !apercu.solveEnCours && !apercu.planVide;
  });

  /**
   * Result of the last incremental re-solve (issue #86), cleared as soon as a
   * full solve replaces the whole plan: the diff would then describe a
   * planning that no longer exists.
   */
  protected readonly incrementalStats = signal<StatistiquesIncremental | null>(null);
  protected readonly incrementalChangements = signal<ChangementAffectation[]>([]);
  protected readonly columnsChangements = ['quand', 'stand', 'avant', 'apres'];

  /**
   * What the last solve replaced (issue #274). A solve announcing only its own
   * score let an operator re-solve a good plan, read "0 hard" and leave with a
   * worse planning without ever being told; this is what makes the trade
   * legible. Null when there is nothing to compare against — first solve of an
   * edition, or a score that could not be established.
   */
  protected readonly planPrecedent = signal<PreviousPlan | null>(null);
  /** The comparison is only worth showing when both scores are known. */
  protected readonly comparaisonPlan = computed(() => {
    const precedent = this.planPrecedent();
    const apres = this.score();
    return precedent?.score && apres ? { avant: precedent.score, apres } : null;
  });
  protected readonly restaurationEnCours = signal(false);

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
   * data-entry screens (see `docs/decisions/0001-cloisonnement-par-edition.md`, §5).
   */
  protected readonly editingLocked = inject(SolverJobService).editingLocked;

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
  protected readonly libelleImpactPublication = computed(() => {
    const impact = this.impactPublication();
    if (!impact) {
      return '';
    }
    const quand = new Date(impact.publieLe).toLocaleString(intlLocale());
    if (impact.personnes === 0) {
      return $localize`:@@solver.impact.aucun:Personne ne change d'emploi du temps par rapport au plan publié le ${quand}:date:.`;
    }
    return $localize`:@@solver.impact.personnes:${impact.personnes}:count: personne(s) changeraient d'emploi du temps par rapport au plan publié le ${quand}:date: — c'est ce que la publication leur dirait.`;
  });
  protected readonly libelleReamorcage = computed(() => {
    const reamorcage = this.reamorcageEffectue();
    if (!reamorcage) {
      return '';
    }
    if (reamorcage.mode === 'AUCUN') {
      return $localize`:@@solver.reamorcage.aFroid:Point de départ : aucun, calcul de zéro.`;
    }
    return reamorcage.postesLiberes > 0
      ? $localize`:@@solver.reamorcage.planAvecLiberes:Point de départ : le plan enregistré, ${reamorcage.postes}:count: postes repris et ${reamorcage.postesLiberes}:liberes: laissés libres (animateur disparu ou devenu indisponible).`
      : $localize`:@@solver.reamorcage.plan:Point de départ : le plan enregistré, ${reamorcage.postes}:count: postes repris.`;
  });

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
  private readonly snapshots = inject(PlanSnapshotStore);
  private readonly dialog = inject(MatDialog);
  private readonly crud = inject(ReferenceCrudService);

  constructor() {
    void this.loadLastRun();
    void this.chargerPointDeDepart();
    void this.problemes.reload();
    void this.loadSolverDuration();
    void this.crud.reload();
    void this.chargerApercuPublication();
    // The score curve (issue #304) is pushed from here on; this one read is
    // what puts a solve already under way on screen at once, rather than at the
    // stream's next tick — and what shows it at all in a browser without
    // `EventSource`. It is the only read of the curve carrying an edition, so
    // it is also the server's chance to refuse one belonging to another.
    void this.jobs.chargerCourbeScore();
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
      // Le solve vient de réécrire le plan : le décompte des personnes à
      // prévenir n'est plus celui d'avant.
      void this.chargerApercuPublication();
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
   * The score curve to draw (issue #304): the running solve's, or the last
   * one's until the next replaces it.
   *
   * <p>Null in the one case that would mislead — a solve has taken the solver
   * but has not announced a first complete solution yet. The curve still on
   * hand is the <em>previous</em> run's, and leaving it up while a new job is
   * described as running would read as that job's progress.</p>
   */
  protected readonly courbeScore = computed(() => {
    const trace = this.jobs.scoreTraceEdition();
    const actif = this.jobs.activeJob();
    return trace && actif && trace.jobId !== actif.id ? null : trace;
  });

  /**
   * Whether the curve is folded to a single line of figures, remembered across
   * visits (see `core/panel-collapse`). Three charts are a lot of screen for
   * someone who launched a fifteen-minute solve and walked away.
   *
   * <p>Folding is a rendering choice and nothing else: the stream stays open
   * and `SolverJobService` keeps recording, so unfolding shows the run from its
   * first point rather than a hole starting where the panel was closed — which
   * would defeat the one reading the curve exists to give.</p>
   */
  protected readonly courbeRepliee = signal(
    readPanelCollapsed(defaultPanelStorage(), SCORE_CURVE_STORAGE_KEY)
  );

  protected readonly courbePoints = computed(() => this.courbeScore()?.points ?? []);
  protected readonly courbeTerminee = computed(() => this.courbeScore()?.termine ?? false);
  /** Elapsed time of the run the curve describes: its right edge, see `score-curve.ts`. */
  protected readonly courbeDureeMs = computed(() => this.courbeScore()?.dureeMs ?? 0);

  /**
   * Whether the curve's card is on screen at all. A run on this edition brings
   * it up even before its first point, so the card appears when the solve
   * starts rather than a few seconds later.
   */
  protected readonly courbeVisible = computed(
    () => this.courbeScore() !== null || (this.jobs.activeJob() !== null && this.editingLocked())
  );

  protected basculerCourbe(): void {
    const replie = !this.courbeRepliee();
    this.courbeRepliee.set(replie);
    writePanelCollapsed(defaultPanelStorage(), SCORE_CURVE_STORAGE_KEY, replie);
  }

  protected readonly libelleBasculeCourbe = computed(() =>
    this.courbeRepliee()
      ? $localize`:@@solver.scoreCurve.deplier:Afficher la courbe`
      : $localize`:@@solver.scoreCurve.replier:Réduire la courbe`
  );

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

  /**
   * Puts back the plan the last solve replaced (issue #274). Offered only when
   * the solve made things worse — the same restore the snapshots screen does,
   * brought to where the user learns they lost something rather than leaving
   * them to find it.
   */
  protected async revenirAuPlanPrecedent(): Promise<void> {
    const precedent = this.planPrecedent();
    if (!precedent || this.restaurationEnCours()) {
      return;
    }
    const confirme = await this.confirm.ask({
      title: $localize`:@@solver.previousPlan.restore.title:Revenir au plan d'avant ?`,
      message: $localize`:@@solver.previousPlan.restore.message:Le résultat de cette résolution est remplacé par le plan qui était enregistré avant elle.`,
      confirmLabel: $localize`:@@solver.previousPlan.restore.confirm:Revenir`,
      danger: true
    });
    if (!confirme) {
      return;
    }
    this.restaurationEnCours.set(true);
    try {
      const resultat = await this.snapshots.restaurer(precedent.snapshotId);
      await this.resolution.reload();
      this.planningState.set(null);
      // The comparison described a plan that is no longer the persisted one.
      this.planPrecedent.set(null);
      this.output.set(
        $localize`:@@solver.previousPlan.restored:${resultat.affectations}:count: affectation(s) restaurée(s) : le plan d'avant la résolution est de nouveau enregistré.`
      );
      void this.chargerApercuPublication();
      void this.problemes.reload();
    } catch (error) {
      this.output.set(errorPrefix(error));
    } finally {
      this.restaurationEnCours.set(false);
    }
  }

  /** What a planned job will do, and to which edition — the two things worth reading in the queue. */
  protected typeFileLabel(job: JobView): string {
    if (job.type === 'SOLVE_INCREMENTAL') {
      return $localize`:@@job.type.solveIncremental:Replanification incrémentale`;
    }
    return job.reamorcage === 'AUCUN'
      ? $localize`:@@job.type.solveAFroid:Calcul du planning (de zéro)`
      : $localize`:@@job.type.solve:Calcul du planning`;
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

  /**
   * « Recommencer de zéro » (issue #174): the one gesture that can lose the
   * plan already reached, so it says so and asks. Not offered at all without
   * a plan — « Calculer le planning » already starts cold then.
   */
  protected async onRecommencerDeZero(): Promise<void> {
    const affectations = this.affectationsEnregistrees() ?? 0;
    const publieLe = this.publicationApercu()?.dernierePublicationLe;
    const message = $localize`:@@solver.aFroid.confirm.message:Le plan enregistré (${affectations}:count: affectations) ne servira pas de point de départ : le calcul repart de rien et peut finir en dessous de lui. Pour l'améliorer plutôt que le remplacer, utilisez « Calculer le planning ».`;
    const avertissement = publieLe
      ? ' ' +
        $localize`:@@solver.aFroid.confirm.publie:Un planning a été publié le ${new Date(publieLe).toLocaleString(intlLocale())}:date: : repartir de zéro peut bousculer beaucoup de personnes déjà prévenues, là où « Calculer le planning » ne bouge que ce qui en vaut la peine.`
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
  protected async onPublier(): Promise<void> {
    if (this.publicationBusy() || !this.publicationPossible()) {
      return;
    }
    const apercu = this.publicationApercu();
    const nombre = apercu ? apercu.nombreConcernes : 0;
    // Raised BEFORE the confirmation, not after it: the button drives it, and
    // leaving it live while the dialog is open lets a second click open a
    // second dialog — two confirmations, two POSTs, two waves of mail. On this
    // action the double click is the failure mode, so the guard has to cover
    // the whole gesture and not only the request.
    this.publicationBusy.set(true);
    this.publicationDestinatairesEnCours.set(nombre);
    try {
      const confirme = await this.confirm.ask({
        title: $localize`:@@publication.confirmTitre:Publier le planning ?`,
        message: $localize`:@@publication.confirmMessage:${nombre}:count: personne(s) recevront leur planning à jour et le détail de ce qui change pour elles. Personne d'autre ne sera sollicité.`,
        confirmLabel: $localize`:@@publication.confirmAction:Publier`
      });
      if (!confirme) {
        return;
      }
      this.output.set($localize`:@@publication.enCours:Publication du planning...`);
      try {
        const rapport = await this.api.post<RapportPublication>('/api/planning/publication', null);
        const resume = resumePublication(rapport);
        this.output.set(resume.details ? `${resume.titre} — ${resume.details}` : resume.titre);
        this.publicationListeOuverte.set(false);
      } catch (error) {
        this.output.set(errorPrefix(error));
      } finally {
        await this.chargerApercuPublication();
      }
    } finally {
      this.publicationBusy.set(false);
    }
  }

  /**
   * Reads who is concerned. Called when the screen opens, when the list is
   * expanded and after a publication — never on a timer: a count that refreshes
   * behind the user's back is a count they stop reading.
   */
  protected async chargerApercuPublication(): Promise<void> {
    try {
      this.publicationApercu.set(await this.api.get<ApercuPublication>('/api/planning/publication'));
    } catch {
      // Le bloc reste muet plutôt que d'annoncer un décompte qu'on n'a pas lu.
      this.publicationApercu.set(null);
    }
  }

  protected async onBasculerListePublication(): Promise<void> {
    const ouverte = !this.publicationListeOuverte();
    this.publicationListeOuverte.set(ouverte);
    if (ouverte) {
      await this.chargerApercuPublication();
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

  /** Best-effort like {@link loadLastRun}: without it the line under the buttons simply stays empty. */
  private async chargerPointDeDepart(): Promise<void> {
    try {
      const statut = await this.api.get<{ assignments?: number }>('/api/planning/persisted/count');
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

