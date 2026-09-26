import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  linkedSignal,
  resource,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { AnalysesApi } from '../../core/api/analyses-api';
import { intlLocale } from '../../core/locale';
import { AlerteView } from '../../core/models';
import {
  AppNotification,
  NotificationService,
  NotificationSeverity,
} from '../../core/notification.service';

const SEVERITY_ICONS: Record<NotificationSeverity, string> = {
  info: 'info',
  warning: 'warning',
  alert: 'error',
};

/**
 * What the Notifications page used to hold, folded under « À traiter
 * aujourd'hui »: the alerts the nightly jobs left, person by person, and the
 * history of the application's messages kept by this browser — read, marked
 * as read, cleared, as before.
 *
 * <p>The two stay apart rather than merged, and both reasons are about not
 * lying to the reader: the alerts come from the server, so « Effacer » —
 * which only empties this browser's storage — must visibly not apply to
 * them; and an alert is closed by doing the thing it names (deciding the
 * échange, filling in the missing address), never by dismissing it. The
 * alerts are only read once the section is unfolded, or asked for through
 * `ouvert` by the lines of « À traiter » that point at them.</p>
 */
@Component({
  selector: 'app-messages-recents',
  imports: [MatButtonModule, MatIconModule],
  templateUrl: './messages-recents.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MessagesRecents {
  /** Unfolded from the outside: a line of « À traiter » naming the alerts of the night. */
  readonly ouvert = input(false);

  protected readonly notifications = inject(NotificationService);
  private readonly analysesApi = inject(AnalysesApi);

  /**
   * Folded by default. The reader's click decides until the page asks again:
   * each time `ouvert` turns true — « Voir qui » followed after the reader
   * folded the section — it unfolds, and `ouvert` falling back to false (the
   * address leaving `#alertes-nuit`) folds nothing the reader opened.
   */
  protected readonly deplie = linkedSignal<boolean, boolean>({
    source: this.ouvert,
    computation: (ouvert, previous) => ouvert || (previous?.value ?? false),
  });

  private readonly alertesResource = resource({
    params: () => (this.deplie() ? true : undefined),
    loader: () => this.analysesApi.alerts(),
  });
  /** A server that cannot answer leaves the local log perfectly usable. */
  protected readonly alertes = computed<AlerteView[]>(() =>
    this.alertesResource.hasValue() ? this.alertesResource.value() : [],
  );

  protected basculer(): void {
    this.deplie.update((deplie) => !deplie);
  }

  /** Unfolded by the page, for a line pointing at the alerts while the address already names them. */
  unfold(): void {
    this.deplie.set(true);
  }

  /** Same three icons as the local log, so one section speaks one language. */
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
   * Messages grouped by day, newest day first. An event week piles up dozens
   * of them; a flat list of timestamps forces the reader to compare dates
   * line by line to find "what happened today".
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
    return date.toLocaleDateString(intlLocale(), {
      weekday: 'long',
      day: 'numeric',
      month: 'long',
    });
  }

  protected icon(severity: AppNotification['severity']): string {
    return SEVERITY_ICONS[severity];
  }

  /** Only the time: the day is already the group heading above the row. */
  protected formattedTime(timestamp: number): string {
    return new Date(timestamp).toLocaleTimeString(intlLocale(), {
      hour: '2-digit',
      minute: '2-digit',
    });
  }
}
