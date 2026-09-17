import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  signal,
  ViewEncapsulation,
} from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCardModule } from '@angular/material/card';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink } from '@angular/router';
import { PlanningApi } from '../../core/api/planning-api';
import { errorPrefix } from '../../core/error-message';
import { RapportTypologies } from '../../core/models';
import { OutputPanel } from '../../shared/output-panel';
import {
  CapFilter,
  EMPTY_FILTERS,
  TypologieFilters,
  barres,
  classeHeures,
  filterTypologies,
  dailyMaximum,
  underTension,
} from './typologies-planning';

/** Which rendering the screen is on. */
export type OngletTypologies = 'table' | 'barres' | 'heatmap' | 'cartes';

/**
 * « Qui tient quoi, et pour quel volume » (issue #590): the persisted plan read
 * by typologie of jeu, on its own screen.
 *
 * <p>It used to be a card at the bottom of the Typologies referential, which
 * made it something one stumbled on while editing labels. The typologie is the
 * axis the FESTIVAL reasons about its games on, and the one a quality rule caps and
 * a hard one quotas: the reading deserves its own address, and four renderings
 * of the same rows — a table to sort by, bars to compare on, a typologie × jour
 * heatmap to answer « quand mes jeux de stratégie tournent-ils », and cards for
 * the organiser's own notes.</p>
 *
 * <p>Every animateur is a link to their timeline: a nominative list one cannot
 * act on is a dead end, and « pourquoi Alice n'a-t-elle jamais tenu ce jeu »
 * is answered on her own screen, not on this one.</p>
 */
@Component({
  selector: 'app-typologies-planning-page',
  imports: [
    DecimalPipe,
    FormsModule,
    MatButtonModule,
    MatButtonToggleModule,
    MatCardModule,
    MatCheckboxModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressBarModule,
    MatSelectModule,
    MatTooltipModule,
    RouterLink,
    OutputPanel,
  ],
  templateUrl: './typologies-planning-page.html',
  // The heatmap tab is the shared grid, down to its cells; the rest of the
  // screen is this page's own.
  styleUrls: ['../../../styles/heatmap.css', './typologies-planning-page.css'],
  // Global by design (AGENTS.md): loaded with the route, unscoped like the heatmap.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TypologiesPlanningPage {
  protected readonly busy = signal(false);
  protected readonly output = signal('');
  protected readonly rapport = signal<RapportTypologies | null>(null);
  protected readonly onglet = signal<OngletTypologies>('table');

  protected readonly search = signal('');
  protected readonly cap = signal<CapFilter>('all');
  protected readonly capMinimum = signal<number | null>(null);
  protected readonly tensionOnly = signal(false);

  protected readonly filters = computed<TypologieFilters>(() => ({
    search: this.search(),
    cap: this.cap(),
    capMinimum: this.capMinimum(),
    tensionOnly: this.tensionOnly(),
  }));

  protected readonly jours = computed(() => this.rapport()?.jours ?? []);
  protected readonly lignes = computed(() =>
    filterTypologies(this.rapport()?.typologies ?? [], this.filters()),
  );
  protected readonly barres = computed(() => barres(this.lignes()));
  protected readonly maximumJour = computed(() => dailyMaximum(this.lignes(), this.jours()));

  /** How many rows the filters hide, so a short table is never mistaken for an empty plan. */
  protected readonly masquees = computed(
    () => (this.rapport()?.typologies.length ?? 0) - this.lignes().length,
  );

  /** True once loaded and nobody holds anything: the plan is empty, or not solved yet. */
  protected readonly aucuneAffectation = computed(
    () =>
      this.rapport() !== null &&
      (this.rapport()?.typologies ?? []).every((ligne) => ligne.postes === 0),
  );

  protected readonly filtresActifs = computed(
    () =>
      this.search().trim() !== '' ||
      this.cap() !== 'all' ||
      this.capMinimum() !== null ||
      this.tensionOnly(),
  );

  protected readonly underTension = underTension;
  protected readonly classeHeures = classeHeures;

  private readonly planningApi = inject(PlanningApi);

  constructor() {
    void this.load();
  }

  protected async load(): Promise<void> {
    this.busy.set(true);
    this.output.set('');
    try {
      this.rapport.set(await this.planningApi.typologiesReport());
    } catch (error) {
      this.rapport.set(null);
      this.output.set(errorPrefix(error));
    } finally {
      this.busy.set(false);
    }
  }

  protected resetFilters(): void {
    this.search.set(EMPTY_FILTERS.search);
    this.cap.set(EMPTY_FILTERS.cap);
    this.capMinimum.set(EMPTY_FILTERS.capMinimum);
    this.tensionOnly.set(EMPTY_FILTERS.tensionOnly);
  }

  /** An empty box is « no minimum », never zero. */
  protected setCapMinimum(valeur: string | number | null): void {
    const parsed = Number(valeur);
    this.capMinimum.set(
      valeur === null || valeur === '' || !Number.isFinite(parsed) || parsed < 1
        ? null
        : Math.round(parsed),
    );
  }

  protected heuresJour(heuresParJour: Record<string, number>, jour: string): number {
    return heuresParJour?.[jour] ?? 0;
  }
}
