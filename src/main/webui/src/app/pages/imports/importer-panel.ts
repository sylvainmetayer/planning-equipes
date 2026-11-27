import {
  ChangeDetectionStrategy,
  Component,
  inject,
  signal,
  ViewEncapsulation,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { ActivatedRoute } from '@angular/router';
import { keepViewInQueryParams } from '../../core/view-query-params';
import { ImportAnimateursPage } from '../import-animateurs/import-animateurs-page';
import { ExemplesCard } from './exemples-card';
import { ImportScenarioCard } from './import-scenario-card';
import { ImportCard, readImportCard } from './imports';
import { YamlValidator } from './yaml-validator';

/**
 * Fichiers › Importer: every file that fills an edition, one card at a time,
 * chosen by `?cible=`.
 *
 * <p>The YAML scenario is the workbook of a whole edition — typologies,
 * locations, stands and their hours, timeslots, day templates, animateurs —
 * and the card it opens on. The animateurs' CSV is the one table file left:
 * the others, and the stand matrix, were withdrawn in 4.0. The examples bundled
 * with the application and the validator close the list; checking a file
 * writes nothing.</p>
 *
 * <p>It owns the `cible` key of the address and nothing else: the page's
 * `onglet` is the Fichiers page's (ADR 0012).</p>
 */
@Component({
  selector: 'app-importer-panel',
  imports: [
    MatButtonToggleModule,
    MatCardModule,
    MatIconModule,
    ExemplesCard,
    ImportAnimateursPage,
    ImportScenarioCard,
    YamlValidator,
  ],
  templateUrl: './importer-panel.html',
  styleUrl: '../../../styles/import-animateurs.css',
  // Global by design (AGENTS.md): loaded with the route, unscoped like the partials it hosts.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ImporterPanel {
  private readonly route = inject(ActivatedRoute);

  protected readonly card = signal<ImportCard>('scenario');

  constructor() {
    // Followed rather than read once: a link to another card of this very
    // page (the scenario card's « Vérifier un fichier ») reuses the component.
    this.route.queryParamMap.pipe(takeUntilDestroyed()).subscribe((params) => {
      this.card.set(readImportCard(params.get('cible')));
    });
    keepViewInQueryParams(() => ({
      cible: this.card() === 'scenario' ? null : this.card(),
    }));
  }

  protected selectCard(card: ImportCard): void {
    this.card.set(card);
  }
}
