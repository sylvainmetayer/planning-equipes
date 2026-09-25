import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  model,
  resource,
  ViewEncapsulation,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink } from '@angular/router';
import { StandsApi } from '../../core/api/stands-api';
import { RapportOuvertures } from '../../core/models';
import { errorText } from '../../core/resource-state';
import { PastilleFerie } from '../../shared/pastille-ferie';
import {
  COUCHES,
  Couche,
  bandLabel,
  buildCalendrierCouches,
  neighbourPage,
  pageDays,
  toggleCouche,
} from './calendrier-couches';

/**
 * « Calendrier combiné » on the Ouvertures page: a week of the event, a line
 * per stand, and in each cell the stand's own hours, the grid's timeslots and
 * the consigne of the day laid over the seats a solve would receive. Read-only
 * and solve-free; a cell opens a menu to the screen that owns each layer.
 *
 * The page owns the report, the filter and the address (`couches`, `du`);
 * this view reads the layers of the days on screen and decides nothing about
 * them — that is the server's, and `calendrier-couches.ts` only lays them out.
 */
@Component({
  selector: 'app-calendrier-couches',
  imports: [
    MatButtonModule,
    MatCheckboxModule,
    MatIconModule,
    MatMenuModule,
    MatTooltipModule,
    RouterLink,
    PastilleFerie,
  ],
  templateUrl: './couches-vue.html',
  styleUrl: './couches-vue.css',
  // Global by design (AGENTS.md): loaded with the route, unscoped like the page's own sheet.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OpeningLayersView {
  private readonly standsApi = inject(StandsApi);

  readonly rapport = input<RapportOuvertures | null>(null);
  /** The stands the page's filter keeps; `null` keeps them all. */
  readonly standIds = input<ReadonlySet<string> | null>(null);
  /** The layers shown: the `couches` query param. */
  readonly couches = model<Couche[]>([...COUCHES]);
  /** The first day of the page on screen: the `du` query param. */
  readonly firstDay = model<string | null>(null);

  protected readonly allLayers = COUCHES;

  private readonly dates = computed(() => (this.rapport()?.jours ?? []).map((jour) => jour.date));
  protected readonly page = computed(() => pageDays(this.dates(), this.firstDay()));
  protected readonly precedente = computed(() => neighbourPage(this.dates(), this.page(), -1));
  protected readonly suivante = computed(() => neighbourPage(this.dates(), this.page(), 1));

  private readonly layersData = resource({
    params: () => {
      const page = this.page();
      // Re-read with the report: a save on the grid changes the layers too.
      this.rapport();
      return page.length === 0 ? undefined : { du: page[0], au: page[page.length - 1] };
    },
    loader: ({ params }) => this.standsApi.openingLayers(params.du, params.au),
  });
  protected readonly chargement = this.layersData.isLoading;
  protected readonly erreur = errorText(this.layersData);

  protected readonly calendrier = computed(() => {
    const rapport = this.rapport();
    if (!rapport || !this.layersData.hasValue()) {
      return null;
    }
    return buildCalendrierCouches(this.layersData.value(), rapport, this.standIds());
  });

  protected readonly visible = computed(() => new Set(this.couches()));

  protected basculer(couche: Couche, visible: boolean): void {
    this.couches.set(toggleCouche(this.couches(), couche, visible));
  }

  protected allerA(date: string | null): void {
    if (date !== null) {
      this.firstDay.set(date === this.dates()[0] ? null : date);
    }
  }

  protected libelleCouche(couche: Couche): string {
    switch (couche) {
      case 'stand':
        return $localize`:@@ouvertures.couches.couche.stand:Horaires du stand`;
      case 'creneaux':
        return $localize`:@@ouvertures.couches.couche.creneaux:Créneaux`;
      case 'consigne':
        return $localize`:@@ouvertures.couches.couche.consigne:Consigne`;
      case 'resultat':
        return $localize`:@@ouvertures.couches.couche.resultat:Sièges`;
    }
  }

  protected bande = bandLabel;

  /** `2026-07-08` → `08/07`. */
  protected libelleJour(date: string): string {
    const [, mois, jour] = date.split('-');
    return `${jour}/${mois}`;
  }
}
