import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';

import { UpdateCheckService } from '../core/update-check.service';

/**
 * Toolbar hint that a newer release than the running one has been published,
 * next to the notifications bell: an icon with a tooltip naming the version,
 * linking to its release notes. Deliberately not a notification — those are
 * about this instance's own data, and an upgrade is an operator's decision to
 * make on their own schedule, not an item to mark as read. Shown only on a
 * tagged build (see `UpdateCheckService`); self-starts the check so it can be
 * dropped once in the app shell like the indicators beside it.
 */
@Component({
  selector: 'app-update-available-indicator',
  imports: [MatButtonModule, MatIconModule, MatTooltipModule],
  template: `
    @if (updates.available(); as update) {
      <a
        matIconButton
        class="update-available-indicator"
        [href]="update.url"
        target="_blank"
        rel="noopener noreferrer"
        [matTooltip]="tooltip()"
        matTooltipPosition="below"
        [attr.aria-label]="tooltip()"
      >
        <mat-icon>new_releases</mat-icon>
      </a>
    }
  `,
  styles: `
    .update-available-indicator {
      color: var(--mat-sys-tertiary);
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class UpdateAvailableIndicator {
  protected readonly updates = inject(UpdateCheckService);

  protected readonly tooltip = computed(() => {
    const version = this.updates.available()?.version ?? '';
    return $localize`:@@updateAvailable.tooltip:Nouvelle version disponible : ${version}:version:`;
  });

  constructor() {
    this.updates.check();
  }
}
