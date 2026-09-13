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
import { ModeMarge, RapportMarge } from '../../core/models';
import { errorText } from '../../core/resource-state';
import { keepViewInQueryParams } from '../../core/view-query-params';
import { SyntheseMarge, TableMarge, buildSynthese, buildTable, lienCellule, signe } from './marge';

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

  protected readonly mode = signal<ModeMarge>('AVANT');
  /** True as soon as the view differs from the one this page opens on. */
  protected readonly viewChanged = computed(() => this.mode() !== 'AVANT');

  private readonly rapportData = resource({
    params: () => ({ mode: this.mode() }),
    loader: ({ params }) => this.analysesApi.margin(params.mode),
  });
  protected readonly loading = this.rapportData.isLoading;
  protected readonly error = errorText(this.rapportData);
  protected readonly rapport = computed<RapportMarge | null>(() =>
    this.rapportData.hasValue() ? this.rapportData.value() : null,
  );

  protected readonly table = computed<TableMarge>(() => buildTable(this.rapport()));
  protected readonly synthese = computed<SyntheseMarge[]>(() => buildSynthese(this.rapport()));

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
    const lignes = this.table().lignes;
    if (lignes.length === 0) {
      return { ligne: 0, colonne: 0 };
    }
    const { ligne, colonne } = this.focusedCell();
    const rowInRange = Math.min(Math.max(ligne, 0), lignes.length - 1);
    const lastColumn = Math.max((lignes[rowInRange]?.cellules.length ?? 1) - 1, 0);
    return { ligne: rowInRange, colonne: Math.min(Math.max(colonne, 0), lastColumn) };
  });

  constructor() {
    this.seedStateFromQueryParams();
    keepViewInQueryParams(() => ({ mode: this.mode() === 'AVANT' ? null : 'apres' }));
  }

  private seedStateFromQueryParams(): void {
    // Anything but the one value this page knows is ignored rather than
    // rendered: an unknown mode would otherwise show the « après » grid under
    // the « avant » toggle.
    if (this.route.snapshot.queryParamMap.get('mode') === 'apres') {
      this.mode.set('APRES');
    }
  }

  protected isFocusedCell(ligne: number, colonne: number): boolean {
    const courante = this.focusedPosition();
    return courante.ligne === ligne && courante.colonne === colonne;
  }

  protected setMode(mode: ModeMarge): void {
    this.mode.set(mode);
  }

  /** Back to the view this page opens on: the margin before any solve. */
  protected resetView(): void {
    this.mode.set('AVANT');
  }

  protected refresh(): void {
    this.rapportData.reload();
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
    const table = this.table();
    const lastRow = table.lignes.length - 1;
    const lastColumn = (table.lignes[ligne]?.cellules.length ?? 1) - 1;
    // Every branch below assigns it, and the default returns.
    let target: { ligne: number; colonne: number };
    switch (event.key) {
      case 'ArrowRight':
        target = { ligne, colonne: Math.min(colonne + 1, lastColumn) };
        break;
      case 'ArrowLeft':
        target = { ligne, colonne: Math.max(colonne - 1, 0) };
        break;
      case 'ArrowDown':
        target = { ligne: Math.min(ligne + 1, lastRow), colonne };
        break;
      case 'ArrowUp':
        target = { ligne: Math.max(ligne - 1, 0), colonne };
        break;
      case 'Home':
        target = { ligne, colonne: 0 };
        break;
      case 'End':
        target = { ligne, colonne: lastColumn };
        break;
      default:
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
    const cellule = this.table().lignes[ligne]?.cellules[colonne];
    if (!cellule) {
      return;
    }
    const lien = lienCellule(this.mode(), cellule.creneauId, cellule.date);
    if (lien) {
      void this.router.navigate([lien.route], { queryParams: lien.queryParams });
    }
  }

  /** Same destination from the synthesis line, which names the same cell. */
  protected openSummaryLine(ligne: SyntheseMarge): void {
    const lien = lienCellule(this.mode(), ligne.creneauId, ligne.date);
    if (lien) {
      void this.router.navigate([lien.route], { queryParams: lien.queryParams });
    }
  }
}
