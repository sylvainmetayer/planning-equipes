import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  resource,
  signal,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { AnalysesApi } from '../../core/api/analyses-api';
import { ConstraintsApi } from '../../core/api/constraints-api';
import { intlLocale } from '../../core/locale';
import { CoherenceReport } from '../../core/models';
import { NotificationService, NotificationSeverity } from '../../core/notification.service';
import {
  defaultPanelStorage,
  readPanelCollapsed,
  writePanelCollapsed,
} from '../../core/panel-collapse';
import { errorText } from '../../core/resource-state';
import { buildFil, filType, filterFeed, FilItem, FilType, graviteLabel } from './fil';

/** Where « messages open » is kept: stored as `true` when open, so unreadable means folded. */
export const MESSAGES_OPEN_STORAGE_KEY = 'planning-equipes.accueil.messagesOpen';

const SEVERITY_ICONS: Record<NotificationSeverity, string> = {
  info: 'info',
  warning: 'warning',
  alert: 'error',
};

/**
 * The one feed of the home screen, under « À traiter aujourd'hui »: the
 * plan's alerts (the nightly sends, the swaps, the weather), the anomalies
 * of the referential and the history of the application's messages, sorted
 * by gravity then by date and narrowed by the type kept in the address
 * (`?type=alertes|blocages|coherence|messages`). The toolbar's bell lands on it.
 *
 * <p>An alert or an anomaly is closed by doing the thing it names, never by
 * dismissing it: only the messages — the history kept by this browser — are
 * read and cleared, and the section says so.</p>
 */
@Component({
  selector: 'app-fil-accueil',
  imports: [MatButtonModule, MatIconModule, RouterLink],
  templateUrl: './fil-accueil.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FilAccueil {
  /** The checklist of the referential, read by the page that also counts it. */
  readonly coherence = input<CoherenceReport | null>(null);
  readonly coherenceErreur = input('');

  protected readonly notifications = inject(NotificationService);
  private readonly analysesApi = inject(AnalysesApi);
  private readonly constraintsApi = inject(ConstraintsApi);
  private readonly router = inject(Router);
  private readonly queryParams = toSignal(inject(ActivatedRoute).queryParamMap, {
    requireSync: true,
  });

  private readonly alertesResource = resource({ loader: () => this.analysesApi.alerts() });
  protected readonly alertesErreur = errorText(this.alertesResource);

  /** The last analysis's hard breaches; a failure is said, like the alerts'. */
  private readonly blocagesResource = resource({ loader: () => this.constraintsApi.catalogue() });
  protected readonly blocagesErreur = errorText(this.blocagesResource);

  /** The type the address asks for; `null` shows everything. */
  protected readonly type = computed(() => filType(this.queryParams().get('type')));

  private readonly tout = computed(() =>
    buildFil(
      this.alertesResource.hasValue() ? this.alertesResource.value() : [],
      this.coherence(),
      this.notifications.notifications(),
      (this.blocagesResource.hasValue() ? this.blocagesResource.value() : null)?.contraintes ?? [],
    ),
  );

  /**
   * The messages are traces of what happened, not things to do: folded by
   * default (to confirm), the choice kept by this browser. `?type=messages`
   * is an explicit request for them and always shows them.
   */
  protected readonly messagesOpen = signal(
    readPanelCollapsed(defaultPanelStorage(), MESSAGES_OPEN_STORAGE_KEY),
  );
  protected readonly messagesCount = computed(
    () => this.tout().filter((item) => item.type === 'messages').length,
  );
  protected readonly messagesShown = computed(
    () => this.type() === 'messages' || (this.type() === null && this.messagesOpen()),
  );
  protected readonly items = computed(() =>
    filterFeed(this.tout(), this.type()).filter(
      (item) => item.type !== 'messages' || this.messagesShown(),
    ),
  );

  protected toggleMessages(): void {
    const open = !this.messagesOpen();
    this.messagesOpen.set(open);
    writePanelCollapsed(defaultPanelStorage(), MESSAGES_OPEN_STORAGE_KEY, open);
  }

  /** The filter's choices, each with how many items it holds. */
  protected readonly filtres = computed(() => {
    const tout = this.tout();
    const compte = (type: FilType) => tout.filter((item) => item.type === type).length;
    return [
      { type: null, libelle: $localize`:@@fil.filtre.tout:Tout`, compte: tout.length },
      {
        type: 'alertes' as const,
        libelle: $localize`:@@fil.filtre.alertes:Alertes du plan`,
        compte: compte('alertes'),
      },
      {
        type: 'blocages' as const,
        libelle: $localize`:@@fil.filtre.blocages:Blocages du plan`,
        compte: compte('blocages'),
      },
      {
        type: 'coherence' as const,
        libelle: $localize`:@@fil.filtre.coherence:Cohérence du référentiel`,
        compte: compte('coherence'),
      },
      {
        type: 'messages' as const,
        libelle: $localize`:@@fil.filtre.messages:Messages`,
        compte: compte('messages'),
      },
    ];
  });

  /** Read again after a solve, a refresh of the page. */
  reload(): void {
    this.alertesResource.reload();
    this.blocagesResource.reload();
  }

  protected icon(item: FilItem): string {
    return SEVERITY_ICONS[item.variante];
  }

  protected graviteLabel = graviteLabel;

  /**
   * « Préparer la consigne » of a weather alert: the consignes tab on its
   * date, the form open on the suggested preset — nothing is applied before
   * the admin saves it. A modified click keeps the browser's own behaviour.
   */
  protected open(event: MouseEvent, lien: string): void {
    if (event.button !== 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) {
      return;
    }
    event.preventDefault();
    void this.router.navigateByUrl(lien);
  }

  protected formattedDateTime(quand: number): string {
    return new Date(quand).toLocaleString(intlLocale());
  }
}
