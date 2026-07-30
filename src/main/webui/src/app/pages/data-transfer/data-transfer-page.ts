import { Component, ElementRef, computed, inject, signal, viewChild } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatListModule } from '@angular/material/list';
import { ApiService } from '../../core/api.service';
import { ImportSummary } from '../../core/models';
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
  templateUrl: './data-transfer-page.html'
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
  private readonly planningState = inject(PlanningStateService);
  private readonly confirm = inject(ConfirmService);

  /** Entity awaiting the file picked in the shared CSV file input. */
  private pendingCsvEntity: CsvEntity | null = null;

  protected async onExportSql(): Promise<void> {
    this.busy.set(true);
    this.output.set('Building the SQL dump...');
    try {
      this.output.set(await this.api.downloadGet('/api/database/export', 'planning-equipes.sql', 'application/sql'));
    } catch (error) {
      this.output.set(`Error: ${message(error)}`);
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
      title: 'Replay this SQL dump?',
      message: `${file.name} replaces the current database content.`,
      confirmLabel: 'Import',
      danger: true
    });
    if (!confirmed) {
      return;
    }
    this.busy.set(true);
    this.output.set(`Importing ${file.name}...`);
    try {
      const summary = await this.api.postRaw<ImportSummary>(
        '/api/database/import',
        await file.text(),
        'application/sql'
      );
      await this.refreshAfterImport();
      this.output.set(summary.message);
    } catch (error) {
      this.output.set(`Error: ${message(error)}`);
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
      title: `Import ${entity} from CSV?`,
      message: `${file.name} replaces every ${entity} row and drops the existing assignments.`,
      confirmLabel: 'Import',
      danger: true
    });
    if (!confirmed) {
      return;
    }
    this.busy.set(true);
    this.output.set(`Importing ${file.name} as ${entity}...`);
    try {
      const summary = await this.api.postRaw<ImportSummary>(
        `/api/import/csv/${entity}`,
        await file.text(),
        'text/csv'
      );
      await this.refreshAfterImport();
      this.output.set(summary.message);
    } catch (error) {
      this.output.set(`Error: ${message(error)}`);
    } finally {
      this.busy.set(false);
    }
  }

  // A bulk import invalidates whatever planning was displayed.
  private async refreshAfterImport(): Promise<void> {
    this.planningState.set(null);
    await this.referenceData.reload();
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
