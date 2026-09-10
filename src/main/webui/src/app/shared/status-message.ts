import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';

export type StatusTone = 'error' | 'warning' | 'success' | 'info';

/**
 * The one place a page reports the outcome of an action.
 *
 * Before this existed, "enregistré", "supprimé", "import terminé" and server
 * errors were plain paragraphs scattered across 26 screens: nothing was
 * announced, and a message rendered below the fold was simply never seen. Here
 * an error is a `role="alert"` (interrupts, because the user's action failed)
 * and a success or a neutral status is a `role="status"` (polite, spoken once
 * the reader finishes its sentence).
 *
 * Empty text renders nothing at all — the live region is created with the
 * message rather than kept empty in the page, which is what makes assistive
 * technology announce it reliably across browsers.
 */
@Component({
  selector: 'app-status-message',
  imports: [MatIconModule],
  template: `
    @if (text()) {
      <p class="status-message" [class]="'status-message-' + tone()" [attr.role]="role()">
        <mat-icon aria-hidden="true">{{ icon() }}</mat-icon>
        <span>{{ text() }}</span>
      </p>
    }
  `,
  styles: `
    .status-message {
      display: flex;
      align-items: flex-start;
      gap: 0.5rem;
      margin: 0.75rem 0 0;
      font: var(--mat-sys-body-medium);
    }
    .status-message mat-icon {
      flex-shrink: 0;
    }
    .status-message-error {
      color: var(--mat-sys-error);
    }
    .status-message-warning {
      color: var(--mat-sys-tertiary);
    }
    .status-message-success {
      color: var(--mat-sys-primary);
    }
    .status-message-info {
      color: var(--mat-sys-on-surface-variant);
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StatusMessage {
  readonly text = input('');
  readonly tone = input<StatusTone>('info');

  protected readonly role = computed(() => (this.tone() === 'error' ? 'alert' : 'status'));
  protected readonly icon = computed(() => {
    switch (this.tone()) {
      case 'error':
        return 'error_outline';
      case 'warning':
        return 'warning_amber';
      case 'success':
        return 'check_circle_outline';
      default:
        return 'info_outline';
    }
  });
}
