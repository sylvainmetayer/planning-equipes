import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  computed,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { RouterLink } from '@angular/router';
import { errorPrefix } from '../../core/error-message';
import { ScenarioImportService } from '../../core/scenario-import.service';
import { SolverJobService } from '../../core/solver-job.service';
import { GelEditionNotice } from '../../shared/gel-edition-notice';
import { OutputPanel } from '../../shared/output-panel';

/**
 * « Scénario » : the one import tab that is not a referential.
 *
 * <p>The five other tabs add to an edition, column by column; a scenario file
 * carries the whole edition — typologies, emplacements, stands, animateurs,
 * créneaux, ad hoc rules, sometimes its legal parameters — and replaces what
 * is there. Which is why it sits here, next to the files an organiser already
 * has, rather than among the settings: the destructive part of the operation
 * is spelled out by the confirmation {@link ScenarioImportService} raises,
 * naming the edition the file routes to.</p>
 */
@Component({
  selector: 'app-import-scenario-card',
  imports: [
    GelEditionNotice,
    MatButtonModule,
    MatCardModule,
    MatIconModule,
    OutputPanel,
    RouterLink,
  ],
  templateUrl: './import-scenario-card.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ImportScenarioCard {
  protected readonly output = signal('');
  protected readonly importing = signal(false);

  /** Per-edition lock: a solve on ANOTHER edition leaves this import available. */
  protected readonly editionLocked = computed(() => this.jobs.editingLocked());

  private readonly fileInput =
    viewChild.required<ElementRef<HTMLInputElement>>('scenarioFileInput');
  private readonly scenarioImport = inject(ScenarioImportService);
  private readonly jobs = inject(SolverJobService);

  protected pickScenarioFile(): void {
    this.fileInput().nativeElement.click();
  }

  protected async onScenarioFileSelected(event: Event): Promise<void> {
    const file = takeFile(event);
    if (!file) {
      return;
    }
    const content = await file.text();
    this.importing.set(true);
    this.output.set(
      $localize`:@@dataSetup.importingScenarioFile:Import de ${file.name}:fileName: en cours...`,
    );
    try {
      const outcome = await this.scenarioImport.importer({
        kind: 'file',
        fileName: file.name,
        content,
      });
      if (outcome.status === 'cancelled') {
        this.output.set('');
        return;
      }
      this.output.set(
        this.scenarioImport.recapitulatif(
          outcome.result,
          $localize`:@@dataSetup.scenarioFileImported:Scénario ${file.name}:fileName: importé : les données de référence sont peuplées.`,
        ),
      );
    } catch (error) {
      this.output.set(errorPrefix(error));
    } finally {
      this.importing.set(false);
    }
  }
}

// Reads the picked file and clears the input so the same file can be picked twice.
function takeFile(event: Event): File | null {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0] ?? null;
  input.value = '';
  return file;
}
