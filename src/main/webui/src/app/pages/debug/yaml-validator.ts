import { ChangeDetectionStrategy, Component, ElementRef, inject, signal, viewChild } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { ApiService } from '../../core/api.service';
import { errorPrefix } from '../../core/error-message';

/** Body of `POST /api/reference-data/valider-scenario-fichier` — see docs/api.md. */
interface ScenarioValidationResult {
  valide: boolean;
  erreurs: string[];
}

/**
 * Uploads a scenario YAML file and reports the structural validation errors
 * ScenarioValidator finds (types, required fields, value ranges — see
 * docs/schema/scenario-schema.json), without importing anything. A
 * diagnostic tool embedded in the Debug page: unlike the "Importer un
 * fichier" button on the Paramètres page, nothing here is ever persisted, so
 * it's safe to try on any file, valid or not.
 */
@Component({
  selector: 'app-yaml-validator',
  imports: [MatCardModule, MatButtonModule, MatIconModule, MatProgressBarModule],
  templateUrl: './yaml-validator.html',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class YamlValidator {
  protected readonly validating = signal(false);
  protected readonly result = signal<ScenarioValidationResult | null>(null);
  /** Pre-translated "<file> is valid." / "<file> contains N error(s):" — built once the result arrives. */
  protected readonly summary = signal('');
  protected readonly error = signal('');

  private readonly fileInput = viewChild.required<ElementRef<HTMLInputElement>>('fileInput');
  private readonly api = inject(ApiService);

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
      const result = await this.api.postRaw<ScenarioValidationResult>(
        '/api/reference-data/valider-scenario-fichier',
        await file.text(),
        'application/x-yaml'
      );
      this.result.set(result);
      this.summary.set(
        result.valide
          ? $localize`:@@yamlValidator.valid:${file.name}:fileName: est valide.`
          : $localize`:@@yamlValidator.invalid:${file.name}:fileName: contient ${result.erreurs.length}:count: erreur(s) :`
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

