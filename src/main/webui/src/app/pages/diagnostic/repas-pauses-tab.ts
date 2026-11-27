import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  resource,
  signal,
  ViewEncapsulation,
} from '@angular/core';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { RouterLink } from '@angular/router';
import { AnalysesApi } from '../../core/api/analyses-api';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { errorText } from '../../core/resource-state';
import {
  currentViewParams,
  keepViewInQueryParams,
  optionalParam,
} from '../../core/view-query-params';
import { formatHeure } from '../../core/time-of-day';
import { StatusMessage } from '../../shared/status-message';
import { IntendanceJour } from '../intendance/intendance-jour';
import { PausesView } from '../pauses/pauses-vue';
import { walksOfDay, joursRepasPauses } from './repas-pauses';

/**
 * The Diagnostic's « Repas et pauses » report: how the day's breaks and meals
 * sit on the plan, in three sections of one day — the legal breaks and who
 * relays them, the meals counted per emplacement for the intendance, and the
 * walks between two consecutive seats. Three readings of the persisted plan,
 * none of them a solve; each is the server's analysis (`PauseAnalyzer`,
 * `IntendanceRepasAnalyzer`, `WalkSequenceAnalyzer`) shown as it is.
 *
 * <p>The day is `?jour=`, read from the address bar and written back. The
 * meal-break placement is a setting of Paramètres, and the rule that holds it
 * « Coupure repas » of Règles: the tab links to both rather than repeating them.</p>
 */
@Component({
  selector: 'app-repas-pauses-tab',
  imports: [
    MatCardModule,
    MatFormFieldModule,
    MatSelectModule,
    RouterLink,
    StatusMessage,
    PausesView,
    IntendanceJour,
  ],
  templateUrl: './repas-pauses-tab.html',
  // Global by design (AGENTS.md): loaded with the route, unscoped like the sections it hosts.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RepasPausesTab {
  private readonly analyses = inject(AnalysesApi);
  private readonly refs = inject(ReferenceDataStore);

  private readonly pauses = resource({ loader: () => this.analyses.breaks() });
  private readonly walks = resource({ loader: () => this.analyses.walks() });
  // Read here for its days only: the meals' own table reads it again, narrowed to the day.
  private readonly intendance = resource({ loader: () => this.analyses.intendance() });
  protected readonly erreur = errorText(this.pauses);
  protected readonly erreurWalks = errorText(this.walks);

  private readonly rapport = computed(() => (this.pauses.hasValue() ? this.pauses.value() : null));
  private readonly rapportWalks = computed(() =>
    this.walks.hasValue() ? this.walks.value() : null,
  );

  protected readonly jours = computed(() =>
    joursRepasPauses(
      this.rapport(),
      this.rapportWalks(),
      this.intendance.hasValue() ? this.intendance.value() : null,
    ),
  );
  private readonly jourDemande = signal('');
  /** The day on screen: the one asked for when the plan has it, else the first. */
  protected readonly jour = computed(() => {
    const jours = this.jours();
    return jours.includes(this.jourDemande()) ? this.jourDemande() : (jours[0] ?? null);
  });

  protected readonly rapportPauses = this.rapport;
  protected readonly enchainements = computed(() => walksOfDay(this.rapportWalks(), this.jour()));
  protected readonly nonGeolocalise = computed(() => this.rapportWalks()?.geolocated === false);

  protected readonly heure = formatHeure;

  constructor() {
    // The address bar, not the router's snapshot: this tab is built each time it is opened.
    this.jourDemande.set(currentViewParams().get('jour') ?? '');
    // A day the plan does not have is not written back while the screen shows
    // the first one; while the days are unknown (loading, failed) it is kept.
    keepViewInQueryParams(() => {
      const jours = this.jours();
      const demande = this.jourDemande();
      const loading =
        this.pauses.isLoading() || this.walks.isLoading() || this.intendance.isLoading();
      return {
        jour: optionalParam(
          loading || jours.length === 0 || jours.includes(demande) ? demande : '',
        ),
      };
    });
  }

  protected choisir(jour: string): void {
    this.jourDemande.set(jour);
  }

  protected animateurNom(id: string | undefined): string {
    const animateur = this.refs.animateurs().find((a) => a.id === id);
    return animateur ? `${animateur.prenom} ${animateur.nom}` : (id ?? '');
  }

  protected standNom(id: string | undefined): string {
    return this.refs.stands().find((s) => s.id === id)?.nom ?? id ?? '';
  }
}
