import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { AnalysesApi } from '../../core/api/analyses-api';
import { intlLocale } from '../../core/locale';
import { AlerteView } from '../../core/models';
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

  private readonly analysesApi = inject(AnalysesApi);

  /**
   * Alerts raised by the nightly jobs (issues #298, #299, #300), kept apart
   * from the log above rather than merged into it.
   *
   * Two reasons, and both are about not lying to the reader: these come from
   * the server, so « Effacer l'historique » — which only empties this
   * browser's storage — must visibly not apply to them; and an alert is
   * closed by doing the thing it names (deciding the échange, filling in the
   * missing address), never by dismissing it.
   */
  protected readonly alertes = signal<AlerteView[]>([]);

  constructor() {
    void this.chargerAlertes();
  }

  /** A server that cannot answer leaves the local log perfectly usable. */
  private async chargerAlertes(): Promise<void> {
    try {
      this.alertes.set(await this.analysesApi.alerts());
    } catch {
      this.alertes.set([]);
    }
  }

  /** Same three icons as the local log, so one page speaks one language. */
  protected alerteIcon(severite: AlerteView['severite']): string {
    if (severite === 'ALERTE') {
      return SEVERITY_ICONS.alert;
    }
    return severite === 'WARNING' ? SEVERITY_ICONS.warning : SEVERITY_ICONS.info;
  }

  /** Reuses the three CSS variants of the local log rather than inventing a fourth. */
  protected alerteVariante(alerte: AlerteView): NotificationSeverity {
    if (alerte.severite === 'ALERTE') {
      return 'alert';
    }
    return alerte.severite === 'WARNING' ? 'warning' : 'info';
  }

  /** The person an alert is about, when it is about one. */
  protected alerteQui(alerte: AlerteView): string | null {
    return alerte.nomAffiche ?? alerte.animateurId;
  }

  protected formattedDateTime(iso: string): string {
    return new Date(iso).toLocaleString(intlLocale());
  }

  /**
   * Notifications grouped by day, newest day first. An event week piles up
   * dozens of them; a flat list of timestamps forces the reader to compare
   * dates line by line to find "what happened today".
   */
  protected readonly journees = computed(() => {
    const journees: { cle: string; libelle: string; notifications: AppNotification[] }[] = [];
    for (const notification of this.notifications.notifications()) {
      const date = new Date(notification.timestamp);
      const key = date.toDateString();
      const last = journees.at(-1);
      if (last?.cle === key) {
        last.notifications.push(notification);
      } else {
        journees.push({ cle: key, libelle: this.libelleJour(date), notifications: [notification] });
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
