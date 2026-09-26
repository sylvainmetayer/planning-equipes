import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink } from '@angular/router';
import { PlanningResolutionStore } from '../core/planning-resolution.store';

/**
 * Toolbar hint, shown on every screen, that reference data was edited after
 * the last solve: the persisted planning may not reflect it anymore. Kept
 * deliberately light (a small icon with a tooltip, not a banner) since
 * solving after every single edit is often not what's wanted while setting up
 * a dataset — this only needs to make sure the user isn't caught unaware.
 * Self-injects its data so it can be dropped once in the app shell.
 *
 * <p>A link to the Solveur, like the same information on the home page: the
 * one thing to do about stale data is to solve again (#709).</p>
 */
@Component({
  selector: 'app-data-stale-indicator',
  imports: [MatIconModule, MatTooltipModule, RouterLink],
  template: `
    @if (resolution.dataStale()) {
      <a
        class="data-stale-indicator"
        routerLink="/solveur"
        [matTooltip]="tooltip()"
        matTooltipPosition="below"
        [attr.aria-label]="tooltip()"
      >
        <mat-icon aria-hidden="true">edit_note</mat-icon>
      </a>
    }
  `,
  styles: `
    .data-stale-indicator {
      color: var(--mat-sys-tertiary);
      margin-right: 0.5rem;
      display: inline-flex;
      align-items: center;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DataStaleIndicator {
  protected readonly resolution = inject(PlanningResolutionStore);

  protected readonly tooltip = computed(
    () =>
      $localize`:@@dataStale.tooltip:Des données de référence ont changé depuis le dernier calcul : le planning affiché peut ne plus être à jour. Cliquez pour ouvrir le Solveur.`,
  );
}
