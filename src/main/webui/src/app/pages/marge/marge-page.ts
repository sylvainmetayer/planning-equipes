import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  computed,
  inject,
  resource,
  signal,
  ViewEncapsulation,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ActivatedRoute, Router } from '@angular/router';
import { AnalysesApi } from '../../core/api/analyses-api';
import { PlanningApi } from '../../core/api/planning-api';
import {
  CelluleTension,
  ModeMarge,
  MotifTension,
  RapportMarge,
  RapportTension,
} from '../../core/models';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { errorText } from '../../core/resource-state';
import { keepViewInQueryParams } from '../../core/view-query-params';
import {
  SyntheseMarge,
  TableMarge,
  buildSynthese,
  buildTable,
  libelleTranche,
  lienCellule,
  signe,
} from './marge';
import {
  SyntheseTension,
  TableTension,
  buildSyntheseTension,
  buildTableTension,
  libelleGravite,
  libelleMotif,
} from './tension';
import { StatusMessage } from '../../shared/status-message';
import { nextGridCell } from '../../core/grid-navigation';

/**
 * The three readings of the page: the margin before and after a solve, and the
 * tension map that crosses the second with the fragility of the same plan.
 */
export type MarginView = ModeMarge | 'TENSION';

/**
 * « Marge disponible » (issue #499): the day × timeslot grid of what is left —
 * the animateurs available at that moment minus the seats still to staff.
 *
 * <p>It sits between two screens that already answer a neighbouring question at
 * another scale: « Besoin en animateurs » proves a recruitment floor over the
 * whole event, and the bench answers in full for one seat of one timeslot.
 * Neither says <em>when</em> the event is tight, which is the question asked
 * when recruiting, when closing one opening window, or when a withdrawal has to
 * be absorbed.</p>
 *
 * <p>The grid is drawn with the shared heatmap stylesheet — the same
 * scaffolding as « Heatmap de charge » — over a divergent scale of its own: the
 * reading here is signed, so the neutral step is zero and not « nothing to
 * report ».</p>
 */
@Component({
  selector: 'app-marge-page',
  imports: [
    StatusMessage,
    MatCardModule,
    MatButtonModule,
    MatButtonToggleModule,
    MatIconModule,
    MatProgressBarModule,
    MatTooltipModule,
  ],
  templateUrl: './marge-page.html',
  styleUrls: ['../../../styles/heatmap.css', './marge-page.css'],
  // Global by design (AGENTS.md): loaded with the route, unscoped like the partial it was.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MargePage {
  private readonly analysesApi = inject(AnalysesApi);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  private readonly planningApi = inject(PlanningApi);
  private readonly store = inject(ReferenceDataStore);

  protected readonly mode = signal<MarginView>('AVANT');
  /** True as soon as the view differs from the one this page opens on. */
  protected readonly viewChanged = computed(() => this.mode() !== 'AVANT');

  private readonly rapportData = resource({
    params: () => {
      const mode = this.mode();
      return mode === 'TENSION' ? undefined : { mode };
    },
    loader: ({ params }) => this.analysesApi.margin(params.mode),
  });
  private readonly tensionData = resource({
    params: () => (this.mode() === 'TENSION' ? { mode: 'TENSION' } : undefined),
    loader: () => this.analysesApi.tension(),
  });
  /** Whether a plan is persisted: the tension reading needs one, like the « après » margin. */
  private readonly persisted = resource({ loader: () => this.planningApi.persistedCount() });
  protected readonly tensionDisponible = computed(
    () => this.persisted.hasValue() && (this.persisted.value()?.assignments ?? 0) > 0,
  );

  protected readonly loading = computed(
    () => this.rapportData.isLoading() || this.tensionData.isLoading(),
  );
  private readonly marginError = errorText(this.rapportData);
  private readonly tensionError = errorText(this.tensionData);
  protected readonly error = computed(() =>
    this.mode() === 'TENSION' ? this.tensionError() : this.marginError(),
  );
  protected readonly rapport = computed<RapportMarge | null>(() =>
    this.mode() !== 'TENSION' && this.rapportData.hasValue() ? this.rapportData.value() : null,
  );
  protected readonly rapportTension = computed<RapportTension | null>(() =>
    this.mode() === 'TENSION' && this.tensionData.hasValue() ? this.tensionData.value() : null,
  );
  protected readonly message = computed(
    () => (this.mode() === 'TENSION' ? this.rapportTension() : this.rapport())?.message ?? null,
  );

  protected readonly table = computed<TableMarge>(() => buildTable(this.rapport()));
  protected readonly synthese = computed<SyntheseMarge[]>(() => buildSynthese(this.rapport()));
  protected readonly tableTension = computed<TableTension>(() =>
    buildTableTension(this.rapportTension()),
  );
  protected readonly syntheseTension = computed<SyntheseTension[]>(() =>
    buildSyntheseTension(this.rapportTension()),
  );

  /** The tension cell whose reasons the side panel lists; `null` until one is opened. */
  protected readonly detail = signal<CelluleTension | null>(null);

  /** Rows × columns of the grid on screen, whichever reading it is. */
  private readonly dimensions = computed(() => {
    const lignes =
      this.mode() === 'TENSION'
        ? this.tableTension().lignes.map((ligne) => ligne.cellules.length)
        : this.table().lignes.map((ligne) => ligne.cellules.length);
    return lignes;
  });

  /**
   * The cell the grid hands the focus to (roving tabindex): one stop for the
   * whole table on Tab, then the arrows move inside it, like the load heatmap.
   */
  protected readonly focusedCell = signal({ ligne: 0, colonne: 0 });

  /**
   * The same position, clamped to the table actually displayed — a mode change
   * reshapes the grid under it, and a position pointing past the last row would
   * leave no cell carrying `tabindex="0"` at all, taking the grid out of the tab
   * order entirely.
   */
  protected readonly focusedPosition = computed(() => {
    const lignes = this.dimensions();
    if (lignes.length === 0) {
      return { ligne: 0, colonne: 0 };
    }
    const { ligne, colonne } = this.focusedCell();
    const rowInRange = Math.min(Math.max(ligne, 0), lignes.length - 1);
    const lastColumn = Math.max((lignes[rowInRange] ?? 1) - 1, 0);
    return { ligne: rowInRange, colonne: Math.min(Math.max(colonne, 0), lastColumn) };
  });

  constructor() {
    this.seedStateFromQueryParams();
    keepViewInQueryParams(() => ({
      mode: this.mode() === 'AVANT' ? null : this.mode() === 'APRES' ? 'apres' : 'tension',
    }));
  }

  private seedStateFromQueryParams(): void {
    // Anything but the values this page knows is ignored rather than
    // rendered: an unknown mode would otherwise show the « après » grid under
    // the « avant » toggle.
    const mode = this.route.snapshot.queryParamMap.get('mode');
    if (mode === 'apres') {
      this.mode.set('APRES');
    } else if (mode === 'tension') {
      this.enterTension();
    }
  }

  /** The tension panel names people and stands: their labels come from the referential. */
  private enterTension(): void {
    this.mode.set('TENSION');
    void this.store.reload(['animateurs', 'stands']);
  }

  protected isFocusedCell(ligne: number, colonne: number): boolean {
    const courante = this.focusedPosition();
    return courante.ligne === ligne && courante.colonne === colonne;
  }

  protected setMode(mode: MarginView): void {
    this.detail.set(null);
    if (mode === 'TENSION') {
      this.enterTension();
    } else {
      this.mode.set(mode);
    }
  }

  /** Back to the view this page opens on: the margin before any solve. */
  protected resetView(): void {
    this.setMode('AVANT');
  }

  protected refresh(): void {
    if (this.mode() === 'TENSION') {
      this.tensionData.reload();
    } else {
      this.rapportData.reload();
    }
    this.persisted.reload();
  }

  protected readonly libelleGravite = libelleGravite;

  /** Why the tension toggle is greyed out, in its tooltip. */
  protected get tensionIndisponible(): string {
    return $localize`:@@marge.tension.indisponible:Il faut un planning enregistré : lancez d'abord une résolution.`;
  }

  protected motifLabel(motif: MotifTension, cellule: CelluleTension): string {
    return libelleMotif(motif, cellule);
  }

  protected trancheDetail(cellule: CelluleTension): string {
    return `J${cellule.jour} · ${libelleTranche(cellule.debut, cellule.fin)}`;
  }

  /** « Prénom Nom », or the id when the referential does not know it (any more). */
  protected nomAnimateur(id: string): string {
    const animateur = this.store.animateurs().find((candidat) => candidat.id === id);
    return animateur ? `${animateur.prenom ?? ''} ${animateur.nom ?? ''}`.trim() || id : id;
  }

  protected nomStand(id: string): string {
    return this.store.stands().find((stand) => stand.id === id)?.nom || id;
  }

  /** A link of the detail panel: the bench, a timeline, the fragility of a stand. */
  protected aller(route: string, queryParams: Record<string, string | number>): void {
    void this.router.navigate([route], { queryParams });
  }

  protected closeDetail(): void {
    this.detail.set(null);
  }

  protected signedLabel(marge: number): string {
    return signe(marge);
  }

  /** Enter and Space follow the cell's link, since the mouse is not the only way in. */
  protected onCellKeydown(event: KeyboardEvent, ligne: number, colonne: number): void {
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      this.openCell(ligne, colonne);
      return;
    }
    const lignes = this.dimensions();
    const lastRow = lignes.length - 1;
    const lastColumn = (lignes[ligne] ?? 1) - 1;
    const target = nextGridCell(event.key, { ligne, colonne }, lastRow, lastColumn);
    if (!target) {
      return;
    }
    event.preventDefault();
    this.focusedCell.set(target);
    const selecteur = `[data-ligne="${target.ligne}"][data-colonne="${target.colonne}"]`;
    this.host.nativeElement.querySelector<HTMLElement>(selecteur)?.focus();
  }

  /**
   * The bench on the timeslot after a solve, the openings of that day before
   * one — see {@link lienCellule}. A cell holding no seat leads nowhere, and
   * says nothing rather than opening an empty screen.
   */
  protected openCell(ligne: number, colonne: number): void {
    const mode = this.mode();
    if (mode === 'TENSION') {
      // A tension cell opens its reasons beside the grid; the links are there.
      this.detail.set(this.tableTension().lignes[ligne]?.cellules[colonne]?.cellule ?? null);
      return;
    }
    const cellule = this.table().lignes[ligne]?.cellules[colonne];
    if (!cellule) {
      return;
    }
    const lien = lienCellule(mode, cellule.creneauId, cellule.date);
    if (lien) {
      void this.router.navigate([lien.route], { queryParams: lien.queryParams });
    }
  }

  /** Same destination from the synthesis line, which names the same cell. */
  protected openSummaryLine(ligne: SyntheseMarge): void {
    const mode = this.mode();
    if (mode === 'TENSION') {
      return;
    }
    const lien = lienCellule(mode, ligne.creneauId, ligne.date);
    if (lien) {
      void this.router.navigate([lien.route], { queryParams: lien.queryParams });
    }
  }
}
