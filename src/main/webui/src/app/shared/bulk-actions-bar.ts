// Action bar of a multi-row selection, shared by the reference-data pages:
// how many rows are ticked, and what can be done to all of them at once.
//
// Presentational only — the page owns the selection and performs the actions.

import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';

@Component({
  selector: 'app-bulk-actions-bar',
  imports: [MatButtonModule, MatIconModule],
  template: `
    <div class="bulk-bar" role="status">
      <span class="bulk-bar-count">{{ countLabel() }}</span>
      @if (filtre()) {
        <!-- The selection follows the filter: "select all" ticks what is on
             screen, not the whole referential. Worth saying before a bulk
             delete, not after. -->
        <span class="bulk-bar-scope" i18n="@@bulk.filteredScope"
          >(lignes affichées par le filtre uniquement)</span
        >
      }
      @if (editable()) {
        <button matButton type="button" [disabled]="disabled()" (click)="edit.emit()">
          <mat-icon>edit</mat-icon>
          <ng-container i18n="@@bulk.editSelection">Modifier la sélection</ng-container>
        </button>
      }
      <button
        matButton
        type="button"
        class="danger-action"
        [disabled]="disabled()"
        (click)="remove.emit()"
      >
        <mat-icon>delete</mat-icon>
        <ng-container i18n="@@bulk.deleteSelection">Supprimer la sélection</ng-container>
      </button>
      <button matButton type="button" (click)="clear.emit()">
        <ng-container i18n="@@bulk.clearSelection">Tout désélectionner</ng-container>
      </button>
    </div>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class BulkActionsBar {
  readonly count = input.required<number>();
  /** Mirrors the page's `editingLocked`: no bulk write while the solver reads the data. */
  readonly disabled = input(false);
  /** False on the pages whose entities share no bulk-editable field (typologies). */
  readonly editable = input(true);
  /** True when a text filter is narrowing the table the selection was made in. */
  readonly filtre = input(false);

  readonly edit = output<void>();
  readonly remove = output<void>();
  readonly clear = output<void>();

  protected readonly countLabel = computed(
    () => $localize`:@@bulk.selectedCount:${this.count()}:count: élément(s) sélectionné(s)`,
  );
}
