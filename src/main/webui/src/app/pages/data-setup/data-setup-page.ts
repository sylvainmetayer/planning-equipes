import { ChangeDetectionStrategy, Component, ElementRef, computed, inject, signal, viewChild } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatSelectModule } from '@angular/material/select';
import { ApiService } from '../../core/api.service';
import { ImportSummary, ImportScenarioResult, ResetSummary } from '../../core/models';
import { NotificationService } from '../../core/notification.service';
import { PlanningResolutionStore } from '../../core/planning-resolution.store';
import { PlanningStateService } from '../../core/planning-state.service';
import { ProblemesStore } from '../../core/problemes.store';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import { SolverSettingsService } from '../../core/solver-settings.service';
import { ConfirmService } from '../../shared/confirm-dialog';
import { FeasibilityBanner } from '../../shared/feasibility-banner';
import { OutputPanel } from '../../shared/output-panel';

/**
 * Data page: seeds/resets the database, exports it as a scenario file, and
 * transfers data via SQL dump. All actions rebuild or replace part of the
 * dataset, so they are locked while any solver job (solve or analysis) is
 * running for the server.
 *
 * Also the last screen before a solve is launched, so it runs the solver-free
 * feasibility check (`GET /api/feasibility`) on entry and after every action
 * that rewrites the dataset: a structurally impossible planning is called out
 * here rather than after a fruitless solve.
 */
@Component({
  selector: 'app-data-setup-page',
  imports: [
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatFormFieldModule,
    MatSelectModule,
    FeasibilityBanner,
    OutputPanel
  ],
  templateUrl: './data-setup-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class DataSetupPage {
  protected readonly output = signal('');
  protected readonly sampleLoading = signal(false);
  protected readonly resetting = signal(false);
  protected readonly exporting = signal(false);
  protected readonly transferBusy = signal(false);
  protected readonly scenarioFileImporting = signal(false);

  /** Scenario files offered by the backend, and the one currently selected. */
  protected readonly scenarios = signal<string[]>([]);
  protected readonly selectedScenario = signal<string | null>(null);

  /** The server-side solver lock: also covers a solve/analysis from another browser. */
  protected readonly solverBusy = computed(() => this.jobs.solverBusy());
  /** Importing/replaying data while a solve reads it would corrupt the run. */
  protected readonly transferLocked = computed(() => this.transferBusy() || this.solverBusy());

  private readonly sqlInput = viewChild.required<ElementRef<HTMLInputElement>>('sqlInput');
  private readonly scenarioFileInput = viewChild.required<ElementRef<HTMLInputElement>>('scenarioFileInput');
  /** Pre-solve diagnostic shown by the banner at the top of the page. */
  protected readonly problemes = inject(ProblemesStore);

  private readonly api = inject(ApiService);
  private readonly planningState = inject(PlanningStateService);
  private readonly referenceData = inject(ReferenceDataStore);
  // Seeding, emptying or replacing the database moves both the resolved
  // groupe de créneaux and the "data edited since the last solve" stamp:
  // refresh the store the toolbar warnings read, or they keep showing the
  // previous dataset.
  private readonly resolution = inject(PlanningResolutionStore);
  private readonly confirm = inject(ConfirmService);
  private readonly jobs = inject(SolverJobService);
  private readonly solverSettings = inject(SolverSettingsService);
  private readonly notifications = inject(NotificationService);

  constructor() {
    void this.loadScenarioList();
    void this.problemes.reloadFeasibility();
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
      this.output.set(
        $localize`:@@dataSetup.scenarioListError:Erreur lors du chargement de la liste des scénarios : ${message(error)}:message:`
      );
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
    this.output.set(
      name
        ? $localize`:@@dataSetup.loadingScenario:Chargement du scénario « ${name}:name: »...`
        : $localize`:@@dataSetup.loadingSample:Chargement du planning d'exemple...`
    );
    try {
      // The scenario is parsed and imported entirely server-side: we only send
      // its name, so a large scenario never travels to the browser and back.
      const url = name
        ? `/api/reference-data/import-scenario?name=${encodeURIComponent(name)}`
        : '/api/reference-data/import-scenario';
      const result = await this.api.post<ImportScenarioResult | null>(url, {});
      await this.refreshAfterImport();
      this.output.set(
        $localize`:@@dataSetup.sampleLoaded:Planning d'exemple chargé. Les données de référence sont peuplées et modifiables depuis les pages de référence.`
      );
      this.notifyDecoupageAuto(result);
    } catch (error) {
      this.output.set($localize`:@@common.errorPrefix:Erreur : ${message(error)}:message:`);
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
      title: $localize`:@@dataSetup.resetConfirmTitle:Vider la base de données ?`,
      message: $localize`:@@dataSetup.resetConfirmMessage:Tous les stands, créneaux, animateurs, affectations et contraintes ad hoc sont supprimés. Rien n'est rechargé.`,
      confirmLabel: $localize`:@@dataSetup.resetConfirmLabel:Vider`,
      danger: true
    });
    if (!confirmed) {
      return;
    }
    this.resetting.set(true);
    this.output.set($localize`:@@dataSetup.resetting:Suppression des données...`);
    try {
      await this.api.post<ResetSummary>('/api/planning/reset', {});
      await this.refreshAfterImport();
      this.output.set(
        $localize`:@@dataSetup.resetDone:Base de données vidée. Chargez un planning d'exemple pour la repeupler.`
      );
    } catch (error) {
      this.output.set($localize`:@@common.errorPrefix:Erreur : ${message(error)}:message:`);
    } finally {
      this.resetting.set(false);
    }
  }

  // Read-only, so it is not gated by solverActionBlocked() like the other two
  // actions: it never touches the dataset, only reads it.
  protected async onExportScenario(): Promise<void> {
    this.exporting.set(true);
    this.output.set($localize`:@@dataSetup.exportingScenario:Export des données actuelles en fichier scénario...`);
    try {
      const result = await this.api.downloadGet('/api/planning/export-scenario', 'scenario.yaml', 'application/x-yaml');
      this.output.set(result);
    } catch (error) {
      this.output.set($localize`:@@common.errorPrefix:Erreur : ${message(error)}:message:`);
    } finally {
      this.exporting.set(false);
    }
  }

  protected pickScenarioFile(): void {
    this.scenarioFileInput().nativeElement.click();
  }

  // Unlike onSqlFileSelected/onCsvFileSelected (removed), no confirm dialog:
  // a scenario import already replaces animateurs/stands the same way
  // "Charger le scénario sélectionné" does, without asking either — this
  // button is the same action, just sourced from disk instead of a bundled
  // name.
  protected async onScenarioFileSelected(event: Event): Promise<void> {
    const file = takeFile(event);
    if (!file) {
      return;
    }
    this.scenarioFileImporting.set(true);
    this.output.set($localize`:@@dataSetup.importingScenarioFile:Import de ${file.name}:fileName: en cours...`);
    try {
      const result = await this.api.postRaw<ImportScenarioResult | null>(
        '/api/reference-data/import-scenario-fichier',
        await file.text(),
        'application/x-yaml'
      );
      await this.refreshAfterImport();
      this.output.set(
        $localize`:@@dataSetup.scenarioFileImported:Scénario ${file.name}:fileName: importé. Les données de référence sont peuplées et modifiables depuis les pages de référence.`
      );
      this.notifyDecoupageAuto(result);
    } catch (error) {
      const errorMessage = message(error);
      this.output.set($localize`:@@common.errorPrefix:Erreur : ${errorMessage}:message:`);
      this.notifications.notify({
        title: $localize`:@@dataSetup.importScenarioFileInvalid:Fichier scénario invalide`,
        message: errorMessage,
        variant: 'error'
      });
    } finally {
      this.scenarioFileImporting.set(false);
    }
  }

  // Surfaces the scenario's decoupageAuto section, when present: the import
  // silently switched the active timeslot group to the auto-generated
  // vacations, so the operator is told which one without having to check the
  // Découpage page themselves.
  private notifyDecoupageAuto(result: ImportScenarioResult | null): void {
    if (!result?.decoupageAutoGroupeCibleNom) {
      return;
    }
    this.notifications.notify({
      title: $localize`:@@dataSetup.decoupageAuto.applied:Découpage automatique appliqué`,
      message: $localize`:@@dataSetup.decoupageAuto.appliedHint:Le groupe de créneaux « ${result.decoupageAutoGroupeCibleNom}:groupeCibleNom: » a été généré et activé.`,
      variant: 'info'
    });
  }

  protected async onExportSql(): Promise<void> {
    this.transferBusy.set(true);
    this.output.set($localize`:@@dataTransfer.buildingSqlDump:Construction du dump SQL...`);
    try {
      this.output.set(await this.api.downloadGet('/api/database/export', 'planning-equipes.sql', 'application/sql'));
    } catch (error) {
      this.output.set($localize`:@@common.errorPrefix:Erreur : ${message(error)}:message:`);
    } finally {
      this.transferBusy.set(false);
    }
  }

  protected pickSqlFile(): void {
    this.sqlInput().nativeElement.click();
  }

  protected async onSqlFileSelected(event: Event): Promise<void> {
    const file = takeFile(event);
    if (!file) {
      return;
    }
    const confirmed = await this.confirm.ask({
      title: $localize`:@@dataTransfer.replaySqlTitle:Rejouer ce dump SQL ?`,
      message: $localize`:@@dataTransfer.replaySqlMessage:${file.name}:fileName: remplace le contenu actuel de la base de données.`,
      confirmLabel: $localize`:@@dataTransfer.importAction:Importer`,
      danger: true
    });
    if (!confirmed) {
      return;
    }
    this.transferBusy.set(true);
    this.output.set($localize`:@@dataTransfer.importing:Import de ${file.name}:fileName: en cours...`);
    try {
      const summary = await this.api.postRaw<ImportSummary>(
        '/api/database/import',
        await file.text(),
        'application/sql'
      );
      await this.refreshAfterImport();
      this.output.set(summary.message);
    } catch (error) {
      this.output.set($localize`:@@common.errorPrefix:Erreur : ${message(error)}:message:`);
    } finally {
      this.transferBusy.set(false);
    }
  }

  // Guards against a race: the buttons are disabled while a solver job runs,
  // but a job could have started between the last render and the click.
  private solverActionBlocked(): boolean {
    if (this.solverBusy()) {
      const description = this.jobs.activeJobDescription();
      this.output.set(
        $localize`:@@dataSetup.lockedByJob:${description}:description: La configuration des données est verrouillée jusqu'à la fin.`
      );
      return true;
    }
    return false;
  }

  // A seed, reset or bulk import invalidates whatever planning was displayed,
  // and moves both the resolved groupe de créneaux and the "data edited since
  // the last solve" stamp the toolbar warnings are computed from. A scenario
  // may also have pinned its own solver duration (see import-scenario), so
  // the field on this page is refreshed too — harmless when unchanged.
  // The feasibility diagnostic is recomputed from the new dataset for the same
  // reason: it is about to drive the decision to launch a solve.
  private async refreshAfterImport(): Promise<void> {
    this.planningState.set(null);
    await Promise.all([
      this.referenceData.reload(),
      this.resolution.reload(),
      this.solverSettings.refresh(),
      this.problemes.reloadFeasibility()
    ]);
  }
}

// Reads the picked file and clears the input so the same file can be picked twice.
function takeFile(event: Event): File | null {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0] ?? null;
  input.value = '';
  return file;
}

function message(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
