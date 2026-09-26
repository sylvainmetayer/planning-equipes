// What an empty referential says: what to do, and where one comes from. The
// referentials fill up in an order — game categories, timeslots, stands,
// animateurs — that the menu alone used to tell; an empty table now names
// the step before it when there is one, offers to add the first row, and on
// an edition with nothing at all, to load an example. The import of the
// page's own file sits between the two (a slot the page fills). While it is
// shown, the page leaves « Ajouter » and « Importer » out of its header: two
// buttons of the same name on one screen say nothing about which is which.

import { ChangeDetectionStrategy, Component, computed, inject, input, output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { RouterLink } from '@angular/router';
import { ReferenceDataStore } from '../core/reference-data.store';

/** The step an empty referential comes after: its screen, and what the button calls it. */
export interface PreviousStep {
  route: string;
  label: string;
  /** The step is still to do: the button says so first. */
  missing: boolean;
}

@Component({
  selector: 'app-empty-state',
  imports: [MatButtonModule, MatIconModule, RouterLink],
  template: `
    <div class="empty-state">
      <p class="empty-state-message"><ng-content /></p>
      <div class="empty-state-actions">
        @if (previous(); as step) {
          @if (step.missing) {
            <a matButton="filled" [routerLink]="step.route">
              <mat-icon>arrow_back</mat-icon>
              {{ step.label }}
            </a>
          }
        }
        @if (addable()) {
          <button matButton="filled" type="button" [disabled]="addDisabled()" (click)="add.emit()">
            <mat-icon>add</mat-icon>
            <ng-container i18n="@@common.add">Ajouter</ng-container>
          </button>
        }
        <!-- The page's « Importer » goes here. -->
        <ng-content select="[empty-state-import]" />
        @if (editionEmpty()) {
          <a matButton routerLink="/fichiers" [queryParams]="{ onglet: 'importer', cible: 'exemples' }">
            <mat-icon>auto_awesome</mat-icon>
            <ng-container i18n="@@emptyState.exemple">Charger un exemple</ng-container>
          </a>
        }
      </div>
    </div>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EmptyState {
  private readonly store = inject(ReferenceDataStore);

  readonly previous = input<PreviousStep | null>(null);
  readonly addable = input(true);
  readonly addDisabled = input(false);
  readonly add = output<void>();

  /** Nothing in any referential: the edition was just created, an example is the fastest start. */
  protected readonly editionEmpty = computed(
    () =>
      this.store.typologies().length === 0 &&
      this.store.creneaux().length === 0 &&
      this.store.stands().length === 0 &&
      this.store.animateurs().length === 0,
  );
}
