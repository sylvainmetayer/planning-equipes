// The filters a referential table is narrowed by, as chips above it: each one
// says what it keeps and carries its cross, and « Tout effacer » widens the
// list back. What a chip means and how it reaches the URL is the page's; this
// only shows them — a filter that narrows a list must be seen doing it.

import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatChipsModule } from '@angular/material/chips';
import { MatIconModule } from '@angular/material/icon';

/** One active filter: the key the page removes it by, and what the chip reads. */
export interface FilterChip {
  key: string;
  label: string;
}

@Component({
  selector: 'app-filter-chips',
  imports: [MatButtonModule, MatChipsModule, MatIconModule],
  template: `
    @if (chips().length > 0) {
      <div class="filter-chips">
        <mat-chip-set i18n-aria-label="@@filterChips.label" aria-label="Filtres actifs">
          @for (chip of chips(); track chip.key) {
            <mat-chip [removable]="true" (removed)="remove.emit(chip.key)" [attr.data-filtre]="chip.key">
              {{ chip.label }}
              <button matChipRemove [attr.aria-label]="removeLabel(chip)">
                <mat-icon>cancel</mat-icon>
              </button>
            </mat-chip>
          }
        </mat-chip-set>
        @if (chips().length > 1) {
          <button matButton type="button" (click)="clear.emit()" i18n="@@filterChips.clear">Tout effacer</button>
        }
      </div>
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FilterChips {
  readonly chips = input<readonly FilterChip[]>([]);
  readonly remove = output<string>();
  readonly clear = output<void>();

  protected removeLabel(chip: FilterChip): string {
    return $localize`:@@filterChips.remove:Retirer le filtre ${chip.label}:filtre:`;
  }
}
