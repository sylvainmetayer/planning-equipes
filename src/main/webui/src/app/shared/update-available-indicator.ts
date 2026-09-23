import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';

import { UpdateCheckService } from '../core/update-check.service';
import { newWindowLabel } from './new-window-link';

/**
 * Toolbar hint that a newer release than the running one has been published,
 * next to the notifications bell: an icon with a tooltip naming the version,
 * linking to its release notes. Deliberately not a notification — those are
 * about this instance's own data, and an upgrade is an operator's decision to
 * make on their own schedule, not an item to mark as read. Shown only on a
 * tagged build, and only to a confirmed administrator — the check itself holds
 * both conditions, see `UpdateCheckService`. It self-starts that check so it
 * can be dropped once in the app shell like the indicators beside it.
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
        [attr.aria-label]="label()"
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

  /** The tooltip, plus the new-window notice its `aria-label` would otherwise hide. */
  protected readonly label = computed(() => newWindowLabel(this.tooltip()));

  constructor() {
    this.updates.check();
  }
}
