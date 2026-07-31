import { Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatSelectModule } from '@angular/material/select';
import { ApiService } from '../../core/api.service';
import { ResetSummary } from '../../core/models';
import { PlanningStateService } from '../../core/planning-state.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import { ConfirmService } from '../../shared/confirm-dialog';
import { OutputPanel } from '../../shared/output-panel';

/**
 * Data setup page: seeds the database with a sample scenario and resets it.
 * Both actions rebuild the whole dataset, so they are locked while any solver
 * job (solve or analysis) is running for the server.
 */
@Component({
  selector: 'app-data-setup-page',
  imports: [MatCardModule, MatButtonModule, MatIconModule, MatFormFieldModule, MatSelectModule, OutputPanel],
  templateUrl: './data-setup-page.html'
})
export class DataSetupPage {
  protected readonly output = signal('');
  protected readonly sampleLoading = signal(false);
  protected readonly resetting = signal(false);
  protected readonly exporting = signal(false);

  /** Scenario files offered by the backend, and the one currently selected. */
  protected readonly scenarios = signal<string[]>([]);
  protected readonly selectedScenario = signal<string | null>(null);

  /** The server-side solver lock: also covers a solve/analysis from another browser. */
  protected readonly solverBusy = computed(() => this.jobs.solverBusy());

  private readonly api = inject(ApiService);
  private readonly planningState = inject(PlanningStateService);
  private readonly referenceData = inject(ReferenceDataStore);
  private readonly confirm = inject(ConfirmService);
  private readonly jobs = inject(SolverJobService);

  constructor() {
    void this.loadScenarioList();
  }

  // Fills the dropdown with the scenario files exposed by the backend. Selects
  // the first one so the "Load" button always has a target.
  private async loadScenarioList(): Promise<void> {
    try {
      const names = await this.api.get<string[]>('/api/planning/scenarios');
      this.scenarios.set(names);
      if (names.length > 0 && !this.selectedScenario()) {
        this.selectedScenario.set(names[0]);
      }
    } catch (error) {
      this.output.set(`Error loading scenario list: ${message(error)}`);
    }
  }

  protected onSelectScenario(name: string): void {
    this.selectedScenario.set(name);
  }

  protected async onLoadSample(): Promise<void> {
    if (this.solverActionBlocked()) {
      return;
    }
    const name = this.selectedScenario();
    this.sampleLoading.set(true);
    this.output.set(name ? `Loading scenario "${name}"...` : 'Loading sample planning...');
    try {
      // The scenario is parsed and imported entirely server-side: we only send
      // its name, so a large scenario never travels to the browser and back.
      const url = name
        ? `/api/reference-data/import-scenario?name=${encodeURIComponent(name)}`
        : '/api/reference-data/import-scenario';
      await this.api.post(url, {});
      await this.referenceData.reload();
      // Nothing is solved yet, and no planning is built in the browser: the
      // problem is assembled server-side when the user launches a solve. The
      // display pages fall back to the persisted planning until then, so a very
      // large scenario never has to be materialised client-side.
      this.planningState.set(null);
      this.output.set('Sample planning loaded. Reference data is populated and editable from the reference pages.');
    } catch (error) {
      this.output.set(`Error: ${message(error)}`);
    } finally {
      this.sampleLoading.set(false);
    }
  }

  // Empties the database entirely: no scenario is reloaded, so the app is left
  // with a blank dataset until a sample is loaded again.
  protected async onResetDatabase(): Promise<void> {
    if (this.solverActionBlocked()) {
      return;
    }
    const confirmed = await this.confirm.ask({
      title: 'Empty the database?',
      message: 'Every stand, timeslot, animator, assignment and ad hoc constraint is deleted. Nothing is reloaded.',
      confirmLabel: 'Empty',
      danger: true
    });
    if (!confirmed) {
      return;
    }
    this.resetting.set(true);
    this.output.set('Emptying database...');
    try {
      await this.api.post<ResetSummary>('/api/planning/reset', {});
      this.planningState.set(null);
      await this.referenceData.reload();
      this.output.set('Database emptied. Load a sample planning to repopulate it.');
    } catch (error) {
      this.output.set(`Error: ${message(error)}`);
    } finally {
      this.resetting.set(false);
    }
  }

  // Read-only, so it is not gated by solverActionBlocked() like the other two
  // actions: it never touches the dataset, only reads it.
  protected async onExportScenario(): Promise<void> {
    this.exporting.set(true);
    this.output.set('Exporting current data as a scenario file...');
    try {
      const result = await this.api.downloadGet('/api/planning/export-scenario', 'scenario.yaml', 'application/x-yaml');
      this.output.set(result);
    } catch (error) {
      this.output.set(`Error: ${message(error)}`);
    } finally {
      this.exporting.set(false);
    }
  }

  // Guards against a race: the buttons are disabled while a solver job runs,
  // but a job could have started between the last render and the click.
  private solverActionBlocked(): boolean {
    if (this.jobs.solverBusy()) {
      this.output.set(`${this.jobs.activeJobDescription()} Data setup is locked until it finishes.`);
      return true;
    }
    return false;
  }
}

function message(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
