import { ChangeDetectionStrategy, Component, ElementRef, computed, inject, signal, viewChild } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatSelectModule } from '@angular/material/select';
import { ApiService } from '../../core/api.service';
import { ImpactImport, ImportSummary, ImportScenarioResult } from '../../core/models';
import { NotificationService } from '../../core/notification.service';
import { PlanningResolutionStore } from '../../core/planning-resolution.store';
import { PlanningStateService } from '../../core/planning-state.service';
import { ProblemesStore } from '../../core/problemes.store';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import { SolverSettingsService } from '../../core/solver-settings.service';
import { PlanSnapshotStore } from '../../core/plan-snapshot.store';
import { ConfirmService } from '../../shared/confirm-dialog';
import { FeasibilityBanner } from '../../shared/feasibility-banner';
import { InstantaneAvantAction } from '../../shared/instantane-avant-action';
import { OutputPanel } from '../../shared/output-panel';

/**
 * Data page: seeds the database from scenario files, exports it as a scenario
 * file, and transfers data via SQL dump (emptying the database lives on the
 * Debug page). All actions rebuild or replace part of the dataset, so they
 * are locked while any solver job (solve or analysis) is running for the
 * server.
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
  protected readonly exporting = signal(false);
  protected readonly transferBusy = signal(false);
  protected readonly scenarioFileImporting = signal(false);

  /** Scenario files offered by the backend, and the one currently selected. */
  protected readonly scenarios = signal<string[]>([]);
  protected readonly selectedScenario = signal<string | null>(null);

  /** The server-side solver lock: also covers a solve/analysis from another browser. */
  protected readonly solverBusy = computed(() => this.jobs.solverBusy());
  /**
   * Scenario imports only write the current edition, so they follow the
   * per-edition lock: a solve running on ANOTHER edition leaves them
   * available (e.g. importing next year's data during a long solve).
   */
  protected readonly editionLocked = computed(() => this.jobs.editingLocked());
  /** SQL dump replay rewrites the WHOLE database, every edition included: locked by any running job. */
  protected readonly transferLocked = computed(() => this.transferBusy() || this.solverBusy());

  private readonly sqlInput = viewChild.required<ElementRef<HTMLInputElement>>('sqlInput');
  private readonly scenarioFileInput = viewChild.required<ElementRef<HTMLInputElement>>('scenarioFileInput');
  /** Pre-solve diagnostic shown by the banner at the top of the page. */
  protected readonly problemes = inject(ProblemesStore);

  private readonly api = inject(ApiService);
  private readonly planningState = inject(PlanningStateService);
  private readonly referenceData = inject(ReferenceDataStore);
  // Seeding, emptying or replacing the database moves both the resolved
  // resolution stamp and the "data edited since the last solve" hint:
  // refresh the store the toolbar warnings read, or they keep showing the
  // previous dataset.
  private readonly resolution = inject(PlanningResolutionStore);
  private readonly confirm = inject(ConfirmService);
  private readonly snapshots = inject(PlanSnapshotStore);
  private readonly instantane = inject(InstantaneAvantAction);

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

  /**
   * The gate of both scenario-import buttons: shows what the import will
   * replace or erase — counted server-side — and, once confirmed, saves an
   * automatic snapshot of the resolved plan (when there is one) so the
   * operation stays reversible on the planning side. `false` aborts.
   */
  private async confirmerImportScenario(intitule: string): Promise<boolean> {
    let impact: ImpactImport | null = null;
    try {
      impact = await this.api.get<ImpactImport>('/api/reference-data/impact-import');
    } catch {
      // Counting is comfort, not safety: without it the dialog still warns.
    }
    const confirme = await this.confirm.ask({
      title: $localize`:@@dataSetup.impact.titre:Importer et remplacer les données ?`,
      message: messageImpactImport(impact, intitule),
      confirmLabel: $localize`:@@dataTransfer.importAction:Importer`,
      danger: true
    });
    if (!confirme) {
      return false;
    }
    if (impact?.planningResolu) {
      try {
        await this.snapshots.capturer(
          $localize`:@@dataSetup.snapshotBefore.libelle:Avant ${intitule}:action:`
        );
        this.notifications.notify({
          title: $localize`:@@dataSetup.impact.instantane:Instantané du plan enregistré avant l'import.`,
          variant: 'info'
        });
      } catch (error) {
        this.notifications.notify({
          title: $localize`:@@dataSetup.snapshotBefore.failed:Instantané non enregistré`,
          message: message(error),
          variant: 'error'
        });
      }
    }
    return true;
  }

  protected async onLoadSample(): Promise<void> {
    if (this.solverActionBlocked()) {
      return;
    }
    const name = this.selectedScenario();
    if (!(await this.confirmerImportScenario(
      $localize`:@@dataSetup.action.importScenario:charger un scénario`))) {
      return;
    }
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

  protected async onScenarioFileSelected(event: Event): Promise<void> {
    const file = takeFile(event);
    if (!file) {
      return;
    }
    if (!(await this.confirmerImportScenario(
      $localize`:@@dataSetup.action.importScenarioFichier:importer un fichier scénario`))) {
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
  // replaced the file's amplitudes with the generated vacations in place, so
  // the operator is told without having to check the Découpage page.
  private notifyDecoupageAuto(result: ImportScenarioResult | null): void {
    if (!result?.decoupageAuto) {
      return;
    }
    this.notifications.notify({
      title: $localize`:@@dataSetup.decoupageAuto.applied:Découpage automatique appliqué`,
      message: $localize`:@@dataSetup.decoupageAuto.appliedHint:Les amplitudes du scénario ont été découpées : l'édition porte désormais les vacations générées.`,
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
    await this.instantane.proposer($localize`:@@dataSetup.action.importSql:rejouer un dump SQL`);
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

  // Guards against a race: the buttons are disabled while a solver job runs
  // on this edition, but a job could have started between the last render and
  // the click.
  private solverActionBlocked(): boolean {
    if (this.editionLocked()) {
      const description = this.jobs.activeJobDescription();
      this.output.set(
        $localize`:@@dataSetup.lockedByJob:${description}:description: La configuration des données est verrouillée jusqu'à la fin.`
      );
      return true;
    }
    return false;
  }

  // A seed, reset or bulk import invalidates whatever planning was displayed,
  // and moves both the resolution stamp and the "data edited since
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

/**
 * The confirmation message of a scenario import, built from the server-side
 * impact counts: what gets replaced, what disappears with it, what stays.
 * With `impact` null (the counting call failed), a generic warning remains —
 * counting is comfort, never the safety net itself.
 */
export function messageImpactImport(impact: ImpactImport | null, intitule: string): string {
  const lignes: string[] = [
    $localize`:@@dataSetup.impact.base:Cette action va ${intitule}:action: : les stands et animateurs sont remplacés par ceux du fichier, et ceux qui n'y figurent pas sont supprimés — avec leurs demandes d'échange, sessions et codes d'accès. Les animateurs conservés gardent leur lien d'espace et leur e-mail.`
  ];
  if (impact) {
    lignes.push(
      $localize`:@@dataSetup.impact.referentiel:Actuellement : ${impact.animateurs}:animateurs: animateur(s) et ${impact.stands}:stands: stand(s).`
    );
    if (impact.planningResolu) {
      lignes.push(
        $localize`:@@dataSetup.impact.planning:Le planning résolu (${impact.postes}:postes: affectation(s)) sera effacé, ainsi que ${impact.verrous}:verrous: verrouillage(s) ; un instantané sera enregistré automatiquement avant l'import.`
      );
    }
    if (impact.demandesEchange > 0) {
      lignes.push(
        $localize`:@@dataSetup.impact.demandes:${impact.demandesEchange}:demandes: demande(s) d'échange (dont ${impact.demandesEnAttente}:enAttente: en attente) seront perdues si leurs créneaux sont remplacés ou leurs animateurs supprimés.`
      );
    }
  }
  return lignes.join(' ');
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
