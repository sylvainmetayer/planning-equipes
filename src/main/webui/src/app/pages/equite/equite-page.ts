import { DecimalPipe, PercentPipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  resource,
  signal,
  ViewEncapsulation,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSortModule, Sort } from '@angular/material/sort';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { PlanningApi } from '../../core/api/planning-api';
import { errorPrefix } from '../../core/error-message';
import { LigneEquite, SyntheseColonne } from '../../core/models';
import { errorText, retainedValue } from '../../core/resource-state';
import {
  NO_SORT,
  keepViewInQueryParams,
  optionalParam,
  readSort,
  sortQueryParams,
} from '../../core/view-query-params';
import { OutputPanel } from '../../shared/output-panel';
import { TableFilter } from '../../shared/table-filter';
import {
  COLONNE_ANIMATEUR,
  gapClass,
  colonnes,
  columnConstraint,
  medianGap,
  filterRows,
  formatColonne,
  heureCourte,
  libelleColonne,
  libelleSolveur,
  sortRows,
  valeurColonne,
} from './equite';

/**
 * « Équité » : one line per assigned animateur of the persisted plan, and for
 * each what the Hours screen does not say — evening, week-end and holiday
 * hours, demanding seats, distinct stands, game categories and locations,
 * honoured wishes and appreciations, worked and rest days, the longest run —
 * each value with its distance to the column's median, coloured. The footer
 * carries the median, min, max and standard deviation of every column.
 *
 * A route of its own rather than more columns on `/hours`: that screen checks
 * the legal ceilings, this one arbitrates before publishing and answers « why
 * me » after. Everything comes from `GET /api/planning/equite`, read
 * server-side from the persisted plan under today's legal parameters — the
 * evening starts where they say — and no solve is launched, here or there.
 */
@Component({
  selector: 'app-equite-page',
  imports: [
    DecimalPipe,
    PercentPipe,
    MatButtonModule,
    MatCardModule,
    MatIconModule,
    MatProgressBarModule,
    MatSortModule,
    MatTableModule,
    MatTooltipModule,
    OutputPanel,
    RouterLink,
    TableFilter,
  ],
  templateUrl: './equite-page.html',
  styleUrl: './equite-page.css',
  // Global by design (AGENTS.md): loaded with the route, unscoped like the partial it was.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EquitePage {
  private readonly planningApi = inject(PlanningApi);
  private readonly route = inject(ActivatedRoute);

  private readonly equite = resource({ loader: () => this.planningApi.equityReport() });
  /** Kept across a failed refresh; the template shows the failure in its place, not a blank card. */
  protected readonly rapport = retainedValue(this.equite);
  protected readonly chargement = this.equite.isLoading;
  protected readonly erreur = errorText(this.equite);

  protected readonly sort = signal<Sort>(NO_SORT);
  protected readonly filtre = signal('');
  protected readonly output = signal('');
  protected readonly exportBusy = signal(false);

  protected readonly colonnes = computed(() => colonnes(this.rapport()));
  protected readonly lignes = computed<LigneEquite[]>(() => this.rapport()?.lignes ?? []);
  protected readonly lignesFiltrees = computed(() => filterRows(this.lignes(), this.filtre()));
  protected readonly lignesAffichees = computed(() => sortRows(this.lignesFiltrees(), this.sort()));
  protected readonly heureSoiree = computed(() => heureCourte(this.rapport()?.heureDebutSoiree));
  /** True as soon as the table shows something other than the report as it came. */
  protected readonly viewChanged = computed(
    () =>
      (this.sort().active !== '' && this.sort().direction !== '') || this.filtre().trim() !== '',
  );

  protected readonly colonneAnimateur = COLONNE_ANIMATEUR;
  protected readonly libelleColonne = libelleColonne;
  protected readonly formatColonne = formatColonne;
  protected readonly valeurColonne = valeurColonne;
  protected readonly gapClass = gapClass;

  constructor() {
    const params = this.route.snapshot.queryParamMap;
    this.sort.set(readSort(params));
    this.filtre.set(params.get('q') ?? '');
    keepViewInQueryParams(() => ({
      ...sortQueryParams(this.sort()),
      q: optionalParam(this.filtre()),
    }));
  }

  protected recharger(): void {
    this.equite.reload();
  }

  /** Back to the report as it came: unsorted, unfiltered. */
  protected resetView(): void {
    this.sort.set(NO_SORT);
    this.filtre.set('');
  }

  protected synthese(colonne: string): SyntheseColonne | undefined {
    return this.rapport()?.syntheses[colonne];
  }

  protected gap(ligne: LigneEquite, colonne: string): number | null {
    return medianGap(valeurColonne(ligne, colonne), this.synthese(colonne));
  }

  /** The header's tooltip: which solver rule measures the column, if any, and whether it is on. */
  protected tooltipColonne(colonne: string): string {
    return libelleSolveur(columnConstraint(this.rapport(), colonne));
  }

  protected measuredBySolver(colonne: string): boolean {
    return columnConstraint(this.rapport(), colonne)?.active === true;
  }

  protected async onExportCsv(): Promise<void> {
    this.exportBusy.set(true);
    this.output.set($localize`:@@equite.exporting:Construction de l'export CSV...`);
    try {
      this.output.set(await this.planningApi.exportEquity());
    } catch (error) {
      this.output.set(errorPrefix(error));
    } finally {
      this.exportBusy.set(false);
    }
  }
}
