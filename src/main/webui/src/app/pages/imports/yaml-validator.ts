import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  inject,
  signal,
  viewChild,
  ViewEncapsulation,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { PlanningApi } from '../../core/api/planning-api';
import { ScenarioValidationResult } from '../../core/models';
import { errorPrefix } from '../../core/error-message';
import { StatusMessage } from '../../shared/status-message';

/**
 * « Vérifier un fichier sans l'importer »: uploads a scenario YAML file to
 * `POST /api/reference-data/valider-scenario-fichier` and reports the
 * structural validation errors ScenarioValidator finds (types, required
 * fields, value ranges — see docs/schema/scenario-schema.json), without
 * importing anything. Next to the scenario import on Fichiers › Importer:
 * unlike it, nothing here is ever persisted, so it is safe to try on any file,
 * valid or not.
 */
@Component({
  selector: 'app-yaml-validator',
  imports: [StatusMessage, MatCardModule, MatButtonModule, MatIconModule, MatProgressBarModule],
  templateUrl: './yaml-validator.html',
  styleUrl: '../../../styles/yaml-validator.css',
  // Global by design (AGENTS.md): loaded with the chunk that hosts it, unscoped like the partial it was.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class YamlValidator {
  protected readonly validating = signal(false);
  protected readonly result = signal<ScenarioValidationResult | null>(null);
  /** Pre-translated "<file> is valid." / "<file> contains N error(s):" — built once the result arrives. */
  protected readonly summary = signal('');
  protected readonly error = signal('');

  private readonly fileInput = viewChild.required<ElementRef<HTMLInputElement>>('fileInput');
  private readonly planningApi = inject(PlanningApi);

  protected pickFile(): void {
    this.fileInput().nativeElement.click();
  }

  protected async onFileSelected(event: Event): Promise<void> {
    const file = takeFile(event);
    if (!file) {
      return;
    }
    this.result.set(null);
    this.summary.set('');
    this.error.set('');
    this.validating.set(true);
    try {
      const result = await this.planningApi.validateScenarioFile(await file.text());
      this.result.set(result);
      this.summary.set(
        result.valide
          ? $localize`:@@yamlValidator.valid:${file.name}:fileName: est valide.`
          : $localize`:@@yamlValidator.invalid:${file.name}:fileName: contient ${result.erreurs.length}:count: erreur(s) :`,
      );
    } catch (err) {
      this.error.set(errorPrefix(err));
    } finally {
      this.validating.set(false);
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
