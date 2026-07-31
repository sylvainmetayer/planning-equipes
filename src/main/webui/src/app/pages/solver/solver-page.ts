import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { ApiService } from '../../core/api.service';
import { intlLocale } from '../../core/locale';
import { FeasibilityReport, PlanningDiagnostic } from '../../core/models';
import { PlanningStateService } from '../../core/planning-state.service';
import { SolverJobService } from '../../core/solver-job.service';
import { FeasibilityBanner } from '../../shared/feasibility-banner';
import { OutputPanel } from '../../shared/output-panel';

/**
 * Solver page: launches the background solve job, and exports the resulting
 * planning (PDF + ICS bundled in one ZIP). The server always analyzes the
 * solve result as part of the same job (see {@code SolverJobService.submitSolve}
 * on the backend), so there is no separate analyze action and no client-side
 * chaining to keep in sync. Seeding and resetting the database live on the
 * Data setup page.
 */
@Component({
  selector: 'app-solver-page',
  imports: [MatCardModule, MatButtonModule, MatIconModule, FeasibilityBanner, OutputPanel],
  templateUrl: './solver-page.html'
})
export class SolverPage {
  protected readonly output = signal('');
  protected readonly feasibility = signal<FeasibilityReport | null>(null);
  protected readonly exportBusy = signal(false);

  /** The server-side lock, not a local flag: it also covers other browsers. */
  protected readonly solverBusy = computed(() => this.jobs.solverBusy());

  /** Completion time of the most recent finished SOLVE or ANALYZE job, if any has ever run. */
  protected readonly lastRunAt = signal<string | null>(null);
  protected readonly formattedLastRun = computed(() => {
    const lastRunAt = this.lastRunAt();
    return lastRunAt ? new Date(lastRunAt).toLocaleString(intlLocale()) : '';
  });

  private readonly api = inject(ApiService);
  private readonly planningState = inject(PlanningStateService);
  private readonly jobs = inject(SolverJobService);

  constructor() {
    void this.loadLastRun();
    // Results are pushed by the job service, whoever started the job: a solve
    // launched from another browser also lands here when it completes, already
    // analyzed.
    this.jobs.onResult('SOLVE', (result) => {
      this.applySolveResult(result as PlanningDiagnostic);
      void this.loadLastRun();
    });
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

  protected async onTimefoldSolve(): Promise<void> {
    if (this.solverJobAlreadyRunning()) {
      return;
    }
    this.output.set($localize`:@@solver.submitting:Envoi de la résolution au solveur en arrière-plan...`);
    this.feasibility.set(null);
    try {
      // The problem is built server-side from the reference data: no planning is
      // uploaded, so even a very large scenario can be solved without hitting the
      // HTTP body limit (which would fail with a network error).
      await this.jobs.submitSolveFromReferenceData();
      this.output.set(
        $localize`:@@solver.submitted:Résolution avec Timefold sur le serveur, puis analyse automatique du résultat. Vous pouvez continuer à naviguer ; une notification apparaîtra à chaque étape, ici et dans tout autre navigateur observant ce serveur.`
      );
    } catch (error) {
      this.output.set($localize`:@@common.errorPrefix:Erreur : ${message(error)}:message:`);
    }
  }

  // Only one solver job at a time for the whole server: a solve and an analysis
  // both run the solver, so they must never be started in parallel — including
  // from two different browsers.
  private solverJobAlreadyRunning(): boolean {
    if (!this.jobs.solverBusy()) {
      return false;
    }
    const description = this.jobs.activeJobDescription();
    this.output.set(
      $localize`:@@solver.alreadyRunning:${description}:description: Veuillez attendre la fin avant d'en démarrer une autre.`
    );
    return true;
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
      this.output.set($localize`:@@common.errorPrefix:Erreur : ${message(error)}:message:`);
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
    this.output.set(JSON.stringify(diagnostic, null, 2));
  }
}

function message(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
