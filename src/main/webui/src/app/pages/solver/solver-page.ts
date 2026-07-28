import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { ApiService } from '../../core/api.service';
import { PlanningDiagnostic, PlanningFestival, ResetSummary } from '../../core/models';
import { PlanningStateService } from '../../core/planning-state.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import { ConfirmService } from '../../shared/confirm-dialog';
import { OutputPanel } from '../../shared/output-panel';

/**
 * Solver page: seeds the database with the sample scenario, resets it, and
 * launches the background solve / analysis jobs. Exports live on their own
 * page, reference data editing on one page per entity.
 */
@Component({
  selector: 'app-solver-page',
  imports: [MatCardModule, MatButtonModule, MatIconModule, OutputPanel],
  templateUrl: './solver-page.html'
})
export class SolverPage {
  protected readonly output = signal('');
  protected readonly sampleLoading = signal(false);
  protected readonly resetting = signal(false);

  /** The server-side lock, not a local flag: it also covers other browsers. */
  protected readonly solverBusy = computed(() => this.jobs.solverBusy());

  private readonly api = inject(ApiService);
  private readonly planningState = inject(PlanningStateService);
  private readonly referenceData = inject(ReferenceDataStore);
  private readonly confirm = inject(ConfirmService);
  private readonly jobs = inject(SolverJobService);

  constructor() {
    // Results are pushed by the job service, whoever started the job: a solve
    // launched from another browser also lands here when it completes.
    this.jobs.onResult('SOLVE', (result) => this.applySolveResult(result as PlanningFestival));
    this.jobs.onResult('ANALYZE', (result) => this.applyAnalyzeResult(result as PlanningDiagnostic));
    // Explains why the solver buttons are locked when the job comes from
    // somewhere else (another tab, another browser, a private window).
    effect(() => {
      const job = this.jobs.activeJob();
      if (job && !job.mine) {
        const description = untracked(() => this.jobs.activeJobDescription());
        this.output.set(`${description} Solver actions are locked until it finishes.`);
      }
    });
  }

  protected async onLoadSample(): Promise<void> {
    this.sampleLoading.set(true);
    this.output.set('Loading sample planning...');
    try {
      const sample = await this.api.get<PlanningFestival>('/api/planning/sample');
      this.planningState.set(sample);
      // Import the sample reference data into the CRUD store so it is editable.
      await this.api.post('/api/reference-data/import', sample);
      await this.referenceData.reload();
      this.output.set('Sample planning loaded. Reference data is populated and editable from the reference pages.');
    } catch (error) {
      this.output.set(`Error: ${message(error)}`);
    } finally {
      this.sampleLoading.set(false);
    }
  }

  // Blank-slate reset: reloads the demo scenario into the database with every
  // seat unassigned, so a test run starts from clean, unsolved data.
  protected async onResetDatabase(): Promise<void> {
    const confirmed = await this.confirm.ask({
      title: 'Reset the database?',
      message: 'The sample scenario replaces every stand, timeslot, animator, assignment and ad hoc constraint.',
      confirmLabel: 'Reset',
      danger: true
    });
    if (!confirmed) {
      return;
    }
    this.resetting.set(true);
    this.output.set('Resetting database...');
    try {
      const summary = await this.api.post<ResetSummary>('/api/planning/reset', {});
      this.planningState.set(null);
      await this.referenceData.reload();
      this.output.set(
        `Database reset: ${summary.animateurs} animators, ${summary.stands} stands, `
          + `${summary.creneaux} timeslots, ${summary.postes} unassigned seats.`
      );
    } catch (error) {
      this.output.set(`Error: ${message(error)}`);
    } finally {
      this.resetting.set(false);
    }
  }

  protected async onTimefoldSolve(): Promise<void> {
    if (this.solverJobAlreadyRunning()) {
      return;
    }
    this.output.set('Submitting solve to the background solver...');
    try {
      await this.jobs.submitSolve(await this.planningToWorkOn());
      this.output.set(
        'Solving with Timefold on the server. You can keep browsing; a notification will pop up when it is done, '
          + 'here and in any other browser watching this server.'
      );
    } catch (error) {
      this.output.set(`Error: ${message(error)}`);
    }
  }

  protected async onAnalyze(): Promise<void> {
    if (this.solverJobAlreadyRunning()) {
      return;
    }
    this.output.set('Submitting analysis to the background solver...');
    try {
      await this.jobs.submitAnalyze(await this.planningToWorkOn());
      this.output.set(
        'Analyzing the solution on the server. You can keep browsing; a notification will pop up when it is done.'
      );
    } catch (error) {
      this.output.set(`Error: ${message(error)}`);
    }
  }

  // Only one solver job at a time for the whole server: a solve and an analysis
  // both run the solver, so they must never be started in parallel — including
  // from two different browsers.
  private solverJobAlreadyRunning(): boolean {
    if (!this.jobs.solverBusy()) {
      return false;
    }
    this.output.set(`${this.jobs.activeJobDescription()} Wait for it to finish before starting another one.`);
    return true;
  }

  // Solver input: the planning solved during this session if any, otherwise a
  // fresh problem built from the reference data.
  private async planningToWorkOn(): Promise<PlanningFestival> {
    return this.planningState.lastSolvedPlanning() ?? this.planningState.buildFromReferenceData();
  }

  private applySolveResult(solved: PlanningFestival): void {
    this.planningState.set(solved);
    this.output.set(JSON.stringify(solved, null, 2));
  }

  private applyAnalyzeResult(analysis: PlanningDiagnostic): void {
    if (analysis.planning) {
      this.planningState.set(analysis.planning);
    }
    this.output.set(JSON.stringify(analysis, null, 2));
  }
}

function message(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
