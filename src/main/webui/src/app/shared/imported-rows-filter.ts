import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';

/**
 * « Filtre : lignes importées (N) — Tout afficher »: said beside the quick
 * filter of a referential list opened by an import card's « Voir les N lignes
 * importées » (`?ids=`, `core/imported-rows.ts`), so that a list showing
 * twelve stands of eighty never passes for the whole referential.
 */
@Component({
  selector: 'app-imported-rows-filter',
  imports: [MatButtonModule, MatIconModule],
  template: `
    <span class="imported-rows-filter">
      <ng-container i18n="@@importedRows.filter">Filtre : lignes importées ({{ count() }})</ng-container>
      <button matButton type="button" (click)="cleared.emit()">
        <mat-icon>filter_alt_off</mat-icon>
        <ng-container i18n="@@importedRows.showAll">Tout afficher</ng-container>
      </button>
    </span>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ImportedRowsFilter {
  readonly count = input.required<number>();
  /** « Tout afficher »: the host drops the filter. */
  readonly cleared = output<void>();
}
