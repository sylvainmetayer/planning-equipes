import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { ApiService } from '../../core/api.service';
import { errorPrefix } from '../../core/error-message';
import { RapportPauses } from '../../core/models';
import { keepViewInQueryParams, optionalParam } from '../../core/view-query-params';
import { WorkInProgressBanner } from '../../shared/work-in-progress-banner';
import {
  GroupeStand,
  groupesDuJour,
  heure,
  JourPauses,
  joursDuRapport,
  libelleRelais,
  LignePausePlanifiee,
  planifieesDuJour,
  syntheseDuJour
} from './pauses';

/**
 * « Pauses » : where the legal breaks fall, day by day and stand by stand —
 * who steps out at the latest when, and who is there to cover.
 *
 * A route of its own rather than a column of `/repos`: that screen answers
 * « who gets a day off », over the whole event; this one lives inside a day,
 * where relays are organised. Everything shown comes from `GET /api/pauses`,
 * computed server-side from the persisted plan under the organiser's current
 * legal parameters — no solve is launched, here or there.
 */
@Component({
  selector: 'app-pauses-page',
  imports: [
    FormsModule,
    MatButtonModule,
    MatCardModule,
    MatCheckboxModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressBarModule,
    MatSelectModule,
    MatTooltipModule,
    RouterLink,
    WorkInProgressBanner
  ],
  templateUrl: './pauses-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class PausesPage {
  private readonly api = inject(ApiService);
  private readonly route = inject(ActivatedRoute);

  protected readonly rapport = signal<RapportPauses | null>(null);
  protected readonly chargement = signal(true);
  protected readonly erreur = signal('');
  /** ISO date picked in the selector; `null` until the report says which days exist. */
  protected readonly jourSelectionne = signal<string | null>(null);
  protected readonly recherche = signal('');
  protected readonly sansRelaisSeulement = signal(false);

  protected readonly messageEssai = signal(
    $localize`:@@pauses.messageEssai:Les pauses sont lues sur le planning persisté et les paramètres légaux du jour ; l'outil ne les planifie pas, il dit où elles tombent et qui peut relayer.`
  );

  protected readonly jours = computed<JourPauses[]>(() => joursDuRapport(this.rapport()));
  /**
   * Resolved rather than corrected by an effect: a stale `?jour=` falls back
   * on the first day instead of blanking the page.
   */
  protected readonly jourCourant = computed<JourPauses | null>(() => {
    const jours = this.jours();
    const voulu = this.jourSelectionne();
    return jours.find((jour) => jour.date === voulu) ?? jours[0] ?? null;
  });
  protected readonly estPremierJour = computed(() => this.jours()[0]?.date === this.jourCourant()?.date);
  protected readonly estDernierJour = computed(
    () => this.jours()[this.jours().length - 1]?.date === this.jourCourant()?.date
  );
  protected readonly groupes = computed<GroupeStand[]>(() =>
    groupesDuJour(this.rapport(), this.jourCourant()?.date ?? null, this.recherche(), this.sansRelaisSeulement())
  );
  protected readonly planifiees = computed<LignePausePlanifiee[]>(() =>
    planifieesDuJour(this.rapport(), this.jourCourant()?.date ?? null, this.recherche())
  );
  protected readonly synthese = computed(() => syntheseDuJour(this.rapport(), this.jourCourant()?.date ?? null));
  /** True as soon as a filter narrows the day; the day itself is navigation, not a filter. */
  protected readonly viewChanged = computed(() => this.recherche().trim() !== '' || this.sansRelaisSeulement());

  protected readonly heure = heure;
  protected readonly libelleRelais = libelleRelais;
  protected readonly jourPrecedentLabel = $localize`:@@pauses.day.previous:Jour précédent`;
  protected readonly jourSuivantLabel = $localize`:@@pauses.day.next:Jour suivant`;

  constructor() {
    const params = this.route.snapshot.queryParamMap;
    this.jourSelectionne.set(params.get('jour'));
    this.recherche.set(params.get('q') ?? '');
    this.sansRelaisSeulement.set(params.get('vue') === 'sans-relais');
    void this.recharger();
    keepViewInQueryParams(() => {
      const courant = this.jourCourant();
      const premier = this.jours()[0];
      return {
        // The first day is the default, and a default is the absence of its param.
        jour: courant && premier && courant.date !== premier.date ? courant.date : null,
        q: optionalParam(this.recherche()),
        vue: this.sansRelaisSeulement() ? 'sans-relais' : null
      };
    });
  }

  protected async recharger(): Promise<void> {
    this.chargement.set(true);
    this.erreur.set('');
    try {
      this.rapport.set(await this.api.get<RapportPauses>('/api/pauses'));
    } catch (error) {
      this.rapport.set(null);
      this.erreur.set(errorPrefix(error));
    } finally {
      this.chargement.set(false);
    }
  }

  protected reinitialiser(): void {
    this.recherche.set('');
    this.sansRelaisSeulement.set(false);
  }

  protected selectionnerJour(date: string): void {
    this.jourSelectionne.set(date);
  }

  /** Steps to the previous/next day the report covers; a no-op at either end. */
  protected decalerJour(delta: number): void {
    const jours = this.jours();
    const index = jours.findIndex((jour) => jour.date === this.jourCourant()?.date);
    const target = jours[index + delta];
    if (target) {
      this.selectionnerJour(target.date);
    }
  }
}
