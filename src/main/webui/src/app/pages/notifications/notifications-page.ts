import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { intlLocale } from '../../core/locale';
import { AppNotification, NotificationService, NotificationSeverity } from '../../core/notification.service';

const SEVERITY_ICONS: Record<NotificationSeverity, string> = {
  info: 'info',
  warning: 'warning',
  alert: 'error'
};

/**
 * Single, persisted place to review every warning/alert/info notification the
 * app has raised — post-solve feasibility issues, failed hard constraints,
 * solver job status, CRUD errors — even after the snack bar that first showed
 * them has dismissed itself. Backed by {@link NotificationService}, which
 * keeps the log in localStorage.
 */
@Component({
  selector: 'app-notifications-page',
  imports: [MatCardModule, MatButtonModule, MatIconModule],
  templateUrl: './notifications-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class NotificationsPage {
  protected readonly notifications = inject(NotificationService);

  protected icon(severity: AppNotification['severity']): string {
    return SEVERITY_ICONS[severity];
  }

  protected formattedTimestamp(timestamp: number): string {
    return new Date(timestamp).toLocaleString(intlLocale());
  }
}
