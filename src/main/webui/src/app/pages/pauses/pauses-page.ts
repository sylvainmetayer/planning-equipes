import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  signal,
  ViewEncapsulation,
} from '@angular/core';
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
import { AnalysesApi } from '../../core/api/analyses-api';
import { errorPrefix } from '../../core/error-message';
import { RapportPauses } from '../../core/models';
import { dayNavigation } from '../../core/day-navigation';
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
  syntheseDuJour,
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
    WorkInProgressBanner,
  ],
  templateUrl: './pauses-page.html',
  styleUrl: './pauses-page.css',
  // Global by design (AGENTS.md): loaded with the route, unscoped like the partial it was.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PausesPage {
  private readonly analysesApi = inject(AnalysesApi);
  private readonly route = inject(ActivatedRoute);

  protected readonly rapport = signal<RapportPauses | null>(null);
  protected readonly chargement = signal(true);
  protected readonly erreur = signal('');
  protected readonly recherche = signal('');
  protected readonly sansRelaisSeulement = signal(false);

  protected readonly messageEssai = signal(
    $localize`:@@pauses.messageEssai:Les pauses sont lues sur le planning persisté et les paramètres légaux du jour ; l'outil ne les planifie pas, il dit où elles tombent et qui peut relayer.`,
  );

  protected readonly jours = computed<JourPauses[]>(() => joursDuRapport(this.rapport()));
  /** ISO date picked in the selector, else the first day the report covers. */
  private readonly navigation = dayNavigation(this.jours, (jour) => jour.date, {
    initial: this.route.snapshot.queryParamMap.get('jour'),
  });
  protected readonly jourCourant = this.navigation.current;
  protected readonly estPremierJour = this.navigation.isFirst;
  protected readonly estDernierJour = this.navigation.isLast;
  protected readonly groupes = computed<GroupeStand[]>(() =>
    groupesDuJour(
      this.rapport(),
      this.jourCourant()?.date ?? null,
      this.recherche(),
      this.sansRelaisSeulement(),
    ),
  );
  protected readonly planifiees = computed<LignePausePlanifiee[]>(() =>
    planifieesDuJour(this.rapport(), this.jourCourant()?.date ?? null, this.recherche()),
  );
  protected readonly synthese = computed(() =>
    syntheseDuJour(this.rapport(), this.jourCourant()?.date ?? null),
  );
  /** True as soon as a filter narrows the day; the day itself is navigation, not a filter. */
  protected readonly viewChanged = computed(
    () => this.recherche().trim() !== '' || this.sansRelaisSeulement(),
  );

  protected readonly heure = heure;
  protected readonly libelleRelais = libelleRelais;
  protected readonly jourPrecedentLabel = $localize`:@@pauses.day.previous:Jour précédent`;
  protected readonly jourSuivantLabel = $localize`:@@pauses.day.next:Jour suivant`;

  constructor() {
    const params = this.route.snapshot.queryParamMap;
    this.recherche.set(params.get('q') ?? '');
    this.sansRelaisSeulement.set(params.get('vue') === 'sans-relais');
    void this.recharger();
    keepViewInQueryParams(() => ({
      jour: this.navigation.queryParam(),
      q: optionalParam(this.recherche()),
      vue: this.sansRelaisSeulement() ? 'sans-relais' : null,
    }));
  }

  protected async recharger(): Promise<void> {
    this.chargement.set(true);
    this.erreur.set('');
    try {
      this.rapport.set(await this.analysesApi.breaks());
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
    this.navigation.select(date);
  }

  protected decalerJour(delta: number): void {
    this.navigation.step(delta);
  }
}
