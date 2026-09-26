// The « ⋯ » of a referential row: modify, duplicate, delete — in a menu
// rather than a row of icons, so a table of a hundred and fifty rows stays a
// table. The name of the row, a link or a button on the page, is the first
// way in; the page may add its own items after the three, projected.

import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';

@Component({
  selector: 'app-row-menu',
  imports: [MatButtonModule, MatIconModule, MatMenuModule],
  template: `
    <button matIconButton type="button" class="row-menu-trigger" [matMenuTriggerFor]="menu"
            [attr.aria-label]="triggerLabel()" [title]="triggerLabel()">
      <mat-icon>more_horiz</mat-icon>
    </button>
    <mat-menu #menu="matMenu">
      <button mat-menu-item type="button" [disabled]="editDisabled()" (click)="edit.emit()">
        <mat-icon>edit</mat-icon>
        <span i18n="@@common.edit">Modifier</span>
      </button>
      @if (duplicable()) {
        <button mat-menu-item type="button" [disabled]="duplicateDisabled()" (click)="duplicate.emit()">
          <mat-icon>content_copy</mat-icon>
          <span i18n="@@rowMenu.duplicate">Dupliquer</span>
        </button>
      }
      <ng-content />
      <button mat-menu-item type="button" class="danger-action" [disabled]="removeDisabled()" (click)="remove.emit()">
        <mat-icon>delete</mat-icon>
        <span i18n="@@common.delete">Supprimer</span>
      </button>
    </mat-menu>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RowMenu {
  /** What the row is called, for the trigger's accessible name. */
  readonly name = input.required<string>();
  readonly editDisabled = input(false);
  readonly duplicateDisabled = input(false);
  readonly removeDisabled = input(false);
  /** A page whose rows have nothing worth copying leaves the item out. */
  readonly duplicable = input(true);
  readonly edit = output<void>();
  readonly duplicate = output<void>();
  readonly remove = output<void>();

  protected triggerLabel(): string {
    return $localize`:@@rowMenu.label:Actions sur ${this.name()}:nom:`;
  }
}
