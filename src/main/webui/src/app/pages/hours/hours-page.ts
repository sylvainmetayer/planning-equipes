import { DecimalPipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  signal,
  ViewEncapsulation,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSortModule, Sort } from '@angular/material/sort';
import { MatTableModule } from '@angular/material/table';
import { ActivatedRoute } from '@angular/router';
import { PlanningApi } from '../../core/api/planning-api';
import {
  DUREE_HEBDOMADAIRE_MAX_HEURES,
  DUREE_HEBDOMADAIRE_MAX_MINEUR_HEURES,
  HeuresAnimateur,
  HeuresRapport,
} from '../../core/models';
import { PlanningStateService } from '../../core/planning-state.service';
import { OutputPanel } from '../../shared/output-panel';
import { errorPrefix } from '../../core/error-message';
import {
  NO_SORT,
  keepViewInQueryParams,
  readSort,
  sortQueryParams,
} from '../../core/view-query-params';

/**
 * Hours screen: hours planned per animateur, broken down by ISO calendar
 * week plus a total, computed server-side from the current planning
 * (`/api/planning/hours`) so the midnight-crossing duration edge case is
 * handled in exactly one place (Creneau.getDureeMinutes on the backend).
 */
@Component({
  selector: 'app-hours-page',
  imports: [
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatProgressBarModule,
    MatTableModule,
    MatSortModule,
    DecimalPipe,
    OutputPanel,
  ],
  templateUrl: './hours-page.html',
  styleUrl: './hours-page.css',
  // Global by design (AGENTS.md): loaded with the route, unscoped like the partial it was.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class HoursPage {
  protected readonly output = signal('');
  protected readonly busy = signal(false);
  protected readonly exportBusy = signal(false);
  protected readonly rapport = signal<HeuresRapport | null>(null);
  /**
   * The weeks, then the total, then the three counters the payroll reads
   * (issue #597). They sit after the total on purpose: the weekly reading is
   * what this screen was built for, and the premiums are what it is asked for
   * once the event is over.
   */
  protected readonly columns = computed(() => [
    'animateur',
    ...(this.rapport()?.semaines ?? []),
    'total',
    'dimanche',
    'jourFerie',
    'nuit',
  ]);
  protected readonly sort = signal<Sort>(NO_SORT);
  /** True as soon as the table is sorted on something other than its source order. */
  protected readonly viewChanged = computed(
    () => this.sort().active !== '' && this.sort().direction !== '',
  );

  /**
   * Weekly ceilings the table marks up. The point of this screen is to catch an
   * overrun, and until now the hours were plain numbers: 52 h and 12 h looked
   * exactly alike. The minor ceiling is flagged as a check rather than a
   * breach, since the table does not know who is a minor — it is the reader who
   * knows, and 35 h is where they should look.
   */
  protected readonly plafondMajeur = DUREE_HEBDOMADAIRE_MAX_HEURES;
  protected readonly plafondMineur = DUREE_HEBDOMADAIRE_MAX_MINEUR_HEURES;

  protected niveauHeures(heures: number): 'depassement' | 'verifier' | 'normal' {
    if (heures > this.plafondMajeur) {
      return 'depassement';
    }
    return heures > this.plafondMineur ? 'verifier' : 'normal';
  }

  protected libelleHeures(heures: number): string {
    switch (this.niveauHeures(heures)) {
      case 'depassement':
        return $localize`:@@hours.cell.depassement:${heures}:heures: h — au-dessus du plafond légal de ${this.plafondMajeur}:plafond: h par semaine`;
      case 'verifier':
        return $localize`:@@hours.cell.verifier:${heures}:heures: h — au-dessus du plafond de ${this.plafondMineur}:plafond: h applicable à un mineur`;
      default:
        return '';
    }
  }
  protected readonly sortedAnimateurs = computed(() => {
    const animateurs = this.rapport()?.animateurs ?? [];
    const { active, direction } = this.sort();
    if (!active || !direction) {
      return animateurs;
    }
    const factor = direction === 'asc' ? 1 : -1;
    return [...animateurs].sort((a, b) => factor * compareByColumn(a, b, active));
  });

  /**
   * Event-wide totals, summed over every animateur: the hours the event
   * actually costs, week by week and overall, plus the average per animateur.
   * Rendered as the table's footer row — a per-animateur table answers "is
   * this person overloaded", never "what does the whole roster amount to".
   */
  protected readonly totaux = computed(() => {
    const rapport = this.rapport();
    const animateurs = rapport?.animateurs ?? [];
    // `number | undefined` rather than `number`: the table's columns come from
    // `rapport.semaines`, and a column nobody has hours in has no entry here.
    // Without that `undefined`, `strictTemplates` calls the template's `?? 0`
    // redundant (NG8102) — when it is the very thing keeping "NaN h" out of the
    // footer row.
    const parSemaine: Record<string, number | undefined> = {};
    for (const semaine of rapport?.semaines ?? []) {
      parSemaine[semaine] = animateurs.reduce(
        (sum, row) => sum + (row.heuresParSemaine[semaine] ?? 0),
        0,
      );
    }
    const total = animateurs.reduce((sum, row) => sum + row.total, 0);
    const somme = (lire: (row: HeuresAnimateur) => number) =>
      animateurs.reduce((sum, row) => sum + lire(row), 0);
    return {
      animateurCount: animateurs.length,
      parSemaine,
      total,
      moyenneParAnimateur: animateurs.length > 0 ? total / animateurs.length : 0,
      dimanche: somme((row) => row.heuresDimanche),
      jourFerie: somme((row) => row.heuresJourFerie),
      dimancheFerie: somme((row) => row.heuresDimancheFerie),
      nuit: somme((row) => row.heuresNuit),
    };
  });

  private readonly planningApi = inject(PlanningApi);
  private readonly planningState = inject(PlanningStateService);
  private readonly route = inject(ActivatedRoute);

  constructor() {
    this.sort.set(readSort(this.route.snapshot.queryParamMap));
    void this.load();
    keepViewInQueryParams(() => sortQueryParams(this.sort()));
  }

  /** Back to the order the report came in. */
  protected resetView(): void {
    this.sort.set(NO_SORT);
  }

  protected async load(): Promise<void> {
    this.busy.set(true);
    this.output.set('');
    try {
      const planning = await this.planningState.require();
      this.rapport.set(await this.planningApi.hoursReport(planning));
    } catch (error) {
      // The report is dropped, unlike the lists of /kpi and /comparateur which
      // survive a failed refresh. It is not an inconsistency: those pages
      // reload a list the server already holds, this one asks the server to
      // *recompute* hours against legal ceilings. A stale total left on screen
      // under a fresh timestamp is exactly the number nobody should act on.
      this.rapport.set(null);
      this.output.set(errorPrefix(error));
    } finally {
      this.busy.set(false);
    }
  }

  protected async onExportCsv(): Promise<void> {
    this.exportBusy.set(true);
    this.output.set($localize`:@@hours.exporting:Construction de l'export CSV...`);
    try {
      const planning = await this.planningState.require();
      this.output.set(await this.planningApi.exportHours(planning));
    } catch (error) {
      this.output.set(errorPrefix(error));
    } finally {
      this.exportBusy.set(false);
    }
  }
}

/** The payroll columns are read off their own field; every other one is a week. */
const COLONNES_PAIE: Record<string, (row: HeuresAnimateur) => number> = {
  total: (row) => row.total,
  dimanche: (row) => row.heuresDimanche,
  jourFerie: (row) => row.heuresJourFerie,
  nuit: (row) => row.heuresNuit,
};

function compareByColumn(a: HeuresAnimateur, b: HeuresAnimateur, column: string): number {
  if (column === 'animateur') {
    return a.nom.localeCompare(b.nom);
  }
  const lire = COLONNES_PAIE[column];
  const valueA = lire ? lire(a) : (a.heuresParSemaine[column] ?? 0);
  const valueB = lire ? lire(b) : (b.heuresParSemaine[column] ?? 0);
  return valueA - valueB;
}
