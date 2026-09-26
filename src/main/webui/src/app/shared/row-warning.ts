// The warning a save raised, kept on its row once the snack bar is gone: a
// point to check that disappears with the notification is a point nobody
// checked. The messages come from `ReferenceCrudService.rowWarnings`, and
// leave with the next save of the row that raises none.

import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';

@Component({
  selector: 'app-row-warning',
  imports: [MatIconModule, MatTooltipModule],
  template: `
    @if (messages().length > 0) {
      <mat-icon class="row-warning-icon" aria-hidden="false" [attr.aria-label]="text()" [matTooltip]="text()"
                matTooltipClass="tooltip-multiligne">report</mat-icon>
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RowWarning {
  readonly messages = input<readonly string[]>([]);
  protected readonly text = computed(
    () =>
      $localize`:@@rowWarning.label:À vérifier depuis le dernier enregistrement :` +
      '\n' +
      this.messages().join('\n'),
  );
}
