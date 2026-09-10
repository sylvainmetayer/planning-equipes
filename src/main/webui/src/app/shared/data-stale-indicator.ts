import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { PlanningResolutionStore } from '../core/planning-resolution.store';

/**
 * Toolbar hint, shown on every screen, that reference data was edited after
 * the last solve: the persisted planning may not reflect it anymore. Kept
 * deliberately light (a small icon with a tooltip, not a banner) since
 * solving after every single edit is often not what's wanted while setting up
 * a dataset — this only needs to make sure the user isn't caught unaware.
 * Self-injects its data so it can be dropped once in the app shell.
 */
@Component({
  selector: 'app-data-stale-indicator',
  imports: [MatIconModule, MatTooltipModule],
  template: `
    @if (resolution.dataStale()) {
      <mat-icon
        class="data-stale-indicator"
        [matTooltip]="tooltip()"
        matTooltipPosition="below"
        [attr.aria-label]="tooltip()"
        >edit_note</mat-icon
      >
    }
  `,
  styles: `
    .data-stale-indicator {
      color: var(--mat-sys-tertiary);
      margin-right: 0.5rem;
      cursor: help;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DataStaleIndicator {
  protected readonly resolution = inject(PlanningResolutionStore);

  protected readonly tooltip = computed(
    () =>
      $localize`:@@dataStale.tooltip:Des données de référence ont été modifiées depuis le dernier calcul du planning. Le résultat affiché peut ne plus être à jour ; relancez le solveur si besoin.`,
  );
}
