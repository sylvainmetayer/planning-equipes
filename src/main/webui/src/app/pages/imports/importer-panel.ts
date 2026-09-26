import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  signal,
  ViewEncapsulation,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { ActivatedRoute } from '@angular/router';
import { ReferentielImportTarget } from '../../core/models';
import { keepViewInQueryParams } from '../../core/view-query-params';
import { ImportAnimateursPage } from '../import-animateurs/import-animateurs-page';
import { ImportGrilleStandsPage } from '../import-grille-stands/import-grille-stands-page';
import { ExemplesCard } from './exemples-card';
import { ImportReferentielCard } from './import-referentiel-card';
import { ImportScenarioCard } from './import-scenario-card';
import { referentialImportTexts } from './import-texts';
import { ImportCard, readImportCard } from './imports';
import { YamlValidator } from './yaml-validator';

/** The referential cards and the target each one posts. */
const TARGETS: Partial<Record<ImportCard, ReferentielImportTarget>> = {
  typologies: 'TYPOLOGIES',
  emplacements: 'EMPLACEMENTS',
  stands: 'STANDS',
  creneaux: 'CRENEAUX',
  'journees-types': 'JOURNEES_TYPES',
};

/**
 * Fichiers › Importer: every file that fills an edition, one card at a time,
 * chosen by `?cible=`.
 *
 * <p>The cards follow the order the data is entered — typologies, emplacements,
 * stands, the timeslot grid, the day templates, animateurs — then the stand
 * matrix, which is not a referential import at all: it writes opening hours
 * onto stands that already exist, and only makes sense once the ones before it
 * are done. The dates come before the animateurs on purpose: an imported off
 * day only survives in an edition that already carries the matching timeslot.
 * The scenario cards close the list, apart from the rest: a scenario does not
 * fill an edition, it replaces one — from a file, or from the examples bundled
 * with the application — and checking a file writes nothing.</p>
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
    ImportReferentielCard,
    ImportAnimateursPage,
    ImportGrilleStandsPage,
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

  protected readonly card = signal<ImportCard>('typologies');

  /** The referential card on screen, with the target it posts and its words; empty for the other cards. */
  protected readonly referentialCard = computed(() => {
    const target = TARGETS[this.card()];
    return target === undefined ? [] : [{ target, ...referentialImportTexts(target) }];
  });

  constructor() {
    // Followed rather than read once: a link to another card of this very
    // page (the scenario card's « Vérifier un fichier ») reuses the component.
    this.route.queryParamMap.pipe(takeUntilDestroyed()).subscribe((params) => {
      this.card.set(readImportCard(params.get('cible')));
    });
    keepViewInQueryParams(() => ({
      cible: this.card() === 'typologies' ? null : this.card(),
    }));
  }

  protected selectCard(card: ImportCard): void {
    this.card.set(card);
  }
}
