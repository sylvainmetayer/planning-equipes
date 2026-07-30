import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { PlanningDiagnostic, PlanningFestival } from '../../core/models';
import { PlanningStateService } from '../../core/planning-state.service';
import { SolverJobService } from '../../core/solver-job.service';
import { OutputPanel } from '../../shared/output-panel';

/**
 * Solver page: launches the background solve job. Each solve started here is
 * followed by an automatic analysis of the result, so there is no separate
 * analyze action. Seeding and resetting the database live on the Data setup
 * page; exports on the Exports page.
 */
@Component({
  selector: 'app-solver-page',
  imports: [MatCardModule, MatButtonModule, MatIconModule, OutputPanel],
  templateUrl: './solver-page.html'
})
export class SolverPage {
  protected readonly output = signal('');

  /** The server-side lock, not a local flag: it also covers other browsers. */
  protected readonly solverBusy = computed(() => this.jobs.solverBusy());

  private readonly planningState = inject(PlanningStateService);
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

  protected async onTimefoldSolve(): Promise<void> {
    if (this.solverJobAlreadyRunning()) {
      return;
    }
    this.output.set('Submitting solve to the background solver...');
    try {
      await this.jobs.submitSolve(await this.planningToWorkOn(), true);
      this.output.set(
        'Solving with Timefold on the server, then analyzing the result automatically. You can keep browsing; '
          + 'a notification will pop up at each step, here and in any other browser watching this server.'
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
    // The analysis chained by the job service will overwrite this shortly.
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
