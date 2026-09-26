import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  OnInit,
  output,
  signal,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { RouterLink } from '@angular/router';
import { DisponibilitesApi } from '../core/api/disponibilites-api';
import { EchangesApi } from '../core/api/echanges-api';
import { errorPrefix } from '../core/error-message';
import { toDateKey } from '../core/date-utils';
import { intlLocale } from '../core/locale';
import { NotificationService } from '../core/notification.service';

/** A guichet's state, whichever it is: the switch, its bounds, and whether it answers today. */
export interface EtatGuichet {
  ouvert: boolean;
  debut: string | null;
  fin: string | null;
  /** The switch AND today's date against the bounds; the collection has no such flag and says `ouvert`. */
  ouvertAujourdhui: boolean;
}

/** Whether `today` falls inside the optional bounds — an absent bound does not bound that side. */
export function withinDates(debut: string | null, fin: string | null, today: string): boolean {
  return (!debut || debut <= today) && (!fin || today <= fin);
}

/** « jusqu'au 10/09 », « à partir du 01/09 » — the bound that matters to a reader, in their locale. */
function dateCourte(iso: string): string {
  return new Date(`${iso}T00:00:00`).toLocaleDateString(intlLocale(), {
    day: '2-digit',
    month: '2-digit',
  });
}

/**
 * The phrase of the state line: open until when, open but outside its dates,
 * or closed. Pure, so the three cases are tested without rendering.
 */
export function phraseGuichet(guichet: 'foire' | 'collecte', etat: EtatGuichet): string {
  const foire = guichet === 'foire';
  if (!etat.ouvert) {
    return foire
      ? $localize`:@@guichet.foire.fermee:Foire au planning fermée`
      : $localize`:@@guichet.collecte.fermee:Collecte fermée`;
  }
  if (!etat.ouvertAujourdhui) {
    return foire
      ? $localize`:@@guichet.foire.horsDates:Foire au planning ouverte, mais hors de ses dates aujourd'hui`
      : $localize`:@@guichet.collecte.horsDates:Collecte ouverte, mais hors de ses dates aujourd'hui`;
  }
  if (etat.fin) {
    const fin = dateCourte(etat.fin);
    return foire
      ? $localize`:@@guichet.foire.ouverteJusquau:Foire au planning ouverte jusqu'au ${fin}:fin:`
      : $localize`:@@guichet.collecte.ouverteJusquau:Collecte ouverte jusqu'au ${fin}:fin:`;
  }
  return foire
    ? $localize`:@@guichet.foire.ouverte:Foire au planning ouverte`
    : $localize`:@@guichet.collecte.ouverte:Collecte ouverte`;
}

/**
 * The one line a guichet keeps on the screen of its decisions — Échanges for
 * the foire, Disponibilités for the collection (issue #720): « Foire ouverte
 * jusqu'au 10/09 · Fermer ». Closing is one click, since closing is the
 * gesture one makes from there; opening, and the dates, are configured on
 * Paramètres › Édition, where the three guichets sit together.
 */
@Component({
  selector: 'app-guichet-etat',
  imports: [MatButtonModule, MatIconModule, RouterLink],
  template: `
    @if (etat(); as courant) {
      <p class="guichet-etat" [class.guichet-etat-ferme]="!courant.ouvert">
        <mat-icon inline aria-hidden="true">{{ courant.ouvert ? 'lock_open' : 'lock' }}</mat-icon>
        <span>{{ phrase() }}</span>
        @if (courant.ouvert) {
          <button matButton type="button" [disabled]="saving()" (click)="fermer()" i18n="@@guichet.fermer">
            Fermer
          </button>
        }
        <a
          matButton
          routerLink="/parametres"
          [queryParams]="{ onglet: 'edition' }"
          fragment="guichets"
          i18n="@@guichet.configurer"
          >Dates et ouverture dans Paramètres</a
        >
      </p>
    }
  `,
  styles: `
    .guichet-etat {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 0.25rem 0.75rem;
      margin: 0 0 1rem;
      padding: 0.25rem 0.75rem;
      border-radius: 0.5rem;
      background: var(--mat-sys-surface-container);
      font: var(--mat-sys-body-medium);
    }
    .guichet-etat-ferme {
      color: var(--mat-sys-on-surface-variant);
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class GuichetEtat implements OnInit {
  readonly guichet = input.required<'foire' | 'collecte'>();
  /** Emitted once the guichet was closed from here, so the page can re-read what depends on it. */
  readonly changed = output<EtatGuichet>();

  private readonly echangesApi = inject(EchangesApi);
  private readonly disponibilitesApi = inject(DisponibilitesApi);
  private readonly notifications = inject(NotificationService);

  protected readonly etat = signal<EtatGuichet | null>(null);
  protected readonly saving = signal(false);
  protected readonly phrase = computed(() => {
    const etat = this.etat();
    return etat ? phraseGuichet(this.guichet(), etat) : '';
  });

  ngOnInit(): void {
    void this.load();
  }

  private async load(): Promise<void> {
    try {
      if (this.guichet() === 'foire') {
        const configuration = await this.echangesApi.configuration();
        this.etat.set({
          ouvert: configuration.foireOuverte,
          debut: configuration.debut,
          fin: configuration.fin,
          ouvertAujourdhui: configuration.ouverteAujourdhui,
        });
      } else {
        const configuration = await this.disponibilitesApi.configuration();
        this.etat.set({
          ouvert: configuration.collecteOuverte,
          debut: configuration.debut,
          fin: configuration.fin,
          ouvertAujourdhui:
            configuration.collecteOuverte &&
            withinDates(configuration.debut, configuration.fin, toDateKey(new Date())),
        });
      }
    } catch {
      // The line is a reminder: without it the page still works.
      this.etat.set(null);
    }
  }

  /** Closes the guichet, its dates kept for the next opening. */
  protected async fermer(): Promise<void> {
    const etat = this.etat();
    if (!etat) {
      return;
    }
    this.saving.set(true);
    try {
      if (this.guichet() === 'foire') {
        await this.echangesApi.saveConfiguration({
          foireOuverte: false,
          debut: etat.debut,
          fin: etat.fin,
        });
        this.notifications.notify({
          title: $localize`:@@echanges.foireFermeeNotif:Foire au planning fermée : les espaces animateurs passent en consultation seule.`,
          variant: 'success',
        });
      } else {
        await this.disponibilitesApi.saveConfiguration({
          collecteOuverte: false,
          debut: etat.debut,
          fin: etat.fin,
          prevenirAnimateurs: false,
        });
        this.notifications.notify({
          title: $localize`:@@dispo.fermeeNotif:Collecte fermée : les espaces animateurs n'acceptent plus de déclaration.`,
          variant: 'success',
        });
      }
      const ferme = { ...etat, ouvert: false, ouvertAujourdhui: false };
      this.etat.set(ferme);
      this.changed.emit(ferme);
    } catch (error) {
      this.notifications.notify({ title: errorPrefix(error), variant: 'error' });
      void this.load();
    } finally {
      this.saving.set(false);
    }
  }
}
