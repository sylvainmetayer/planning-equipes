import { ChangeDetectionStrategy, Component, input, model } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';

/**
 * Quick text filter of a reference-data table: one field narrowing the rows
 * as the user types, so a wanted stand/animateur/emplacement/typologie is
 * reached without scrolling a table of several dozen (or several hundred)
 * rows.
 *
 * Only holds the text: what a row is matched against belongs to the page
 * (see `core/text-filter.ts`), which knows its own columns.
 */
@Component({
  selector: 'app-table-filter',
  imports: [FormsModule, MatButtonModule, MatFormFieldModule, MatIconModule, MatInputModule],
  template: `
    <mat-form-field appearance="outline" class="table-filter">
      <mat-label>{{ label() }}</mat-label>
      <mat-icon matPrefix>search</mat-icon>
      <!-- data-page-filter is what the global "/" shortcut jumps to
           (core/keyboard-shortcuts.service.ts): a page declares which of its
           fields is the filter, instead of the shortcut guessing. -->
      <input
        matInput
        type="search"
        name="tableFilter"
        data-page-filter=""
        [ngModel]="value()"
        (ngModelChange)="value.set($event)"
        [placeholder]="placeholder()"
        [attr.aria-label]="label()"
      />
      @if (value()) {
        <button matIconButton matSuffix type="button" [attr.aria-label]="clearLabel" [title]="clearLabel"
                (click)="value.set('')">
          <mat-icon>close</mat-icon>
        </button>
      }
      @if (value()) {
        <!-- Announced, not just shown: the count is the feedback of the typing,
             and a screen-reader user otherwise filters blind. -->
        <mat-hint role="status" i18n="@@filter.matchCount">{{ matches() }} ligne(s) sur {{ total() }}</mat-hint>
      }
    </mat-form-field>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class TableFilter {
  /** Two-way bound to the page's filter signal. */
  readonly value = model.required<string>();
  readonly label = input($localize`:@@filter.label:Filtrer`);
  readonly placeholder = input($localize`:@@filter.placeholder:Nom, identifiant…`);
  /** Rows the filter keeps, out of {@link total} — shown as a hint while a filter is typed. */
  readonly matches = input(0);
  readonly total = input(0);

  protected readonly clearLabel = $localize`:@@filter.clear:Effacer le filtre`;
}
