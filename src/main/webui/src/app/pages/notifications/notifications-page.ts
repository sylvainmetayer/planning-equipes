import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
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

  /**
   * Notifications grouped by day, newest day first. A festival week piles up
   * dozens of them; a flat list of timestamps forces the reader to compare
   * dates line by line to find "what happened today".
   */
  protected readonly journees = computed(() => {
    const journees: { cle: string; libelle: string; notifications: AppNotification[] }[] = [];
    for (const notification of this.notifications.notifications()) {
      const date = new Date(notification.timestamp);
      const cle = date.toDateString();
      const derniere = journees.at(-1);
      if (derniere?.cle === cle) {
        derniere.notifications.push(notification);
      } else {
        journees.push({ cle, libelle: this.libelleJour(date), notifications: [notification] });
      }
    }
    return journees;
  });

  /** "Aujourd'hui" / "Hier" beat a date the reader has to decode. */
  private libelleJour(date: Date): string {
    const jour = new Date(date).setHours(0, 0, 0, 0);
    const aujourdhui = new Date().setHours(0, 0, 0, 0);
    const unJour = 24 * 60 * 60 * 1000;
    if (jour === aujourdhui) {
      return $localize`:@@notifications.today:Aujourd'hui`;
    }
    if (jour === aujourdhui - unJour) {
      return $localize`:@@notifications.yesterday:Hier`;
    }
    return date.toLocaleDateString(intlLocale(), { weekday: 'long', day: 'numeric', month: 'long' });
  }

  protected icon(severity: AppNotification['severity']): string {
    return SEVERITY_ICONS[severity];
  }

  protected formattedTimestamp(timestamp: number): string {
    return new Date(timestamp).toLocaleString(intlLocale());
  }

  /** Only the time: the day is already the group heading above the row. */
  protected formattedTime(timestamp: number): string {
    return new Date(timestamp).toLocaleTimeString(intlLocale(), { hour: '2-digit', minute: '2-digit' });
  }
}
