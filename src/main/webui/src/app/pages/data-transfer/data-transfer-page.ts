import { ChangeDetectionStrategy, Component, ElementRef, computed, inject, signal, viewChild } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatListModule } from '@angular/material/list';
import { ApiService } from '../../core/api.service';
import { ImportSummary } from '../../core/models';
import { PlanningResolutionStore } from '../../core/planning-resolution.store';
import { PlanningStateService } from '../../core/planning-state.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import { ConfirmService } from '../../shared/confirm-dialog';
import { OutputPanel } from '../../shared/output-panel';

type CsvEntity = 'animateurs' | 'stands' | 'creneaux';

/**
 * Data transfer page: SQL dump export/import and CSV imports of the reference
 * data. Every import replaces data, so each one asks for a confirmation.
 */
@Component({
  selector: 'app-data-transfer-page',
  imports: [MatCardModule, MatButtonModule, MatIconModule, MatListModule, OutputPanel],
  templateUrl: './data-transfer-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class DataTransferPage {
  protected readonly output = signal('');
  protected readonly busy = signal(false);
  protected readonly jobs = inject(SolverJobService);
  /** Importing/replaying data while a solve reads it would corrupt the run. */
  protected readonly transferLocked = computed(() => this.busy() || this.jobs.solverBusy());

  private readonly sqlInput = viewChild.required<ElementRef<HTMLInputElement>>('sqlInput');
  private readonly csvInput = viewChild.required<ElementRef<HTMLInputElement>>('csvInput');
  private readonly api = inject(ApiService);
  private readonly referenceData = inject(ReferenceDataStore);
  private readonly resolution = inject(PlanningResolutionStore);
  private readonly planningState = inject(PlanningStateService);
  private readonly confirm = inject(ConfirmService);

  /** Entity awaiting the file picked in the shared CSV file input. */
  private pendingCsvEntity: CsvEntity | null = null;

  protected async onExportSql(): Promise<void> {
    this.busy.set(true);
    this.output.set($localize`:@@dataTransfer.buildingSqlDump:Construction du dump SQL...`);
    try {
      this.output.set(await this.api.downloadGet('/api/database/export', 'planning-equipes.sql', 'application/sql'));
    } catch (error) {
      this.output.set($localize`:@@common.errorPrefix:Erreur : ${message(error)}:message:`);
    } finally {
      this.busy.set(false);
    }
  }

  protected pickSqlFile(): void {
    this.sqlInput().nativeElement.click();
  }

  protected pickCsvFile(entity: CsvEntity): void {
    this.pendingCsvEntity = entity;
    this.csvInput().nativeElement.click();
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
    this.busy.set(true);
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
      this.busy.set(false);
    }
  }

  protected async onCsvFileSelected(event: Event): Promise<void> {
    const entity = this.pendingCsvEntity;
    const file = takeFile(event);
    this.pendingCsvEntity = null;
    if (!file || !entity) {
      return;
    }
    const confirmed = await this.confirm.ask({
      title: $localize`:@@dataTransfer.importCsvTitle:Importer ${entity}:entity: depuis un CSV ?`,
      message: $localize`:@@dataTransfer.importCsvMessage:${file.name}:fileName: remplace toutes les lignes de ${entity}:entity: et supprime les affectations existantes.`,
      confirmLabel: $localize`:@@dataTransfer.importAction:Importer`,
      danger: true
    });
    if (!confirmed) {
      return;
    }
    this.busy.set(true);
    this.output.set($localize`:@@dataTransfer.importingAs:Import de ${file.name}:fileName: en tant que ${entity}:entity:...`);
    try {
      const summary = await this.api.postRaw<ImportSummary>(
        `/api/import/csv/${entity}`,
        await file.text(),
        'text/csv'
      );
      await this.refreshAfterImport();
      this.output.set(summary.message);
    } catch (error) {
      this.output.set($localize`:@@common.errorPrefix:Erreur : ${message(error)}:message:`);
    } finally {
      this.busy.set(false);
    }
  }

  // A bulk import invalidates whatever planning was displayed, and moves both
  // the resolved groupe de créneaux and the "data edited since the last solve"
  // stamp the toolbar warnings are computed from.
  private async refreshAfterImport(): Promise<void> {
    this.planningState.set(null);
    await Promise.all([this.referenceData.reload(), this.resolution.reload()]);
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
