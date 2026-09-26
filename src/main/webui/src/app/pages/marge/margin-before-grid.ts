import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  computed,
  inject,
  input,
  signal,
  ViewEncapsulation,
} from '@angular/core';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Router } from '@angular/router';
import { nextGridCell } from '../../core/grid-navigation';
import { RapportMarge } from '../../core/models';
import { buildTable, lienCellule, TableMarge } from './marge';

/**
 * The margin before any solve, day × timeslot: who has not declared the date
 * unavailable, minus the seats a solve would have to fill. The Besoin tab's
 * per-day table carries its tightest cell as a column; this is the whole grid
 * behind it, each cell opening the openings of its day — what one closes to
 * move the margin before a solve.
 */
@Component({
  selector: 'app-margin-before-grid',
  imports: [MatTooltipModule],
  template: `
    <div class="heatmap-legend">
      <span class="heatmap-legend-item"><span class="heatmap-swatch marge-cell-deficitFort"></span><ng-container i18n="@@marge.legend.deficitFort">Déficit marqué</ng-container></span>
      <span class="heatmap-legend-item"><span class="heatmap-swatch marge-cell-deficit"></span><ng-container i18n="@@marge.legend.deficit">Déficit</ng-container></span>
      <span class="heatmap-legend-item"><span class="heatmap-swatch marge-cell-neutre"></span><ng-container i18n="@@marge.legend.neutre">Juste à l'équilibre</ng-container></span>
      <span class="heatmap-legend-item"><span class="heatmap-swatch marge-cell-surplus"></span><ng-container i18n="@@marge.legend.surplus">Marge</ng-container></span>
      <span class="heatmap-legend-item"><span class="heatmap-swatch marge-cell-surplusFort"></span><ng-container i18n="@@marge.legend.surplusFort">Marge confortable</ng-container></span>
      <span class="heatmap-legend-item"><span class="heatmap-swatch marge-cell-vide"></span><ng-container i18n="@@marge.legend.vide">Aucun siège</ng-container></span>
    </div>
    <div class="table-wrapper">
      <table class="heatmap-table" role="grid">
        <caption class="visually-hidden" i18n="@@marge.table.caption">Marge disponible : une ligne par journée de l'événement, une colonne par tranche horaire de la grille</caption>
        <thead>
          <tr>
            <th scope="col" class="heatmap-row-header" i18n="@@marge.column.jour">Journée</th>
            @for (colonne of table().colonnes; track colonne.cle) {
              <th scope="col" class="heatmap-day-header">{{ colonne.label }}</th>
            }
          </tr>
        </thead>
        <tbody>
          @for (ligne of table().lignes; track ligne.cle; let indexLigne = $index) {
            <tr>
              <th class="heatmap-row-header" scope="row">{{ ligne.label }}</th>
              @for (cellule of ligne.cellules; track cellule.cle; let indexColonne = $index) {
                <td
                  [class]="'heatmap-cell marge-cell marge-cell-' + cellule.niveau"
                  [class.marge-cell-lien]="cellule.niveau !== 'vide'"
                  [attr.data-ligne]="indexLigne"
                  [attr.data-colonne]="indexColonne"
                  [tabindex]="isFocusedCell(indexLigne, indexColonne) ? 0 : -1"
                  (focus)="focusedCell.set({ ligne: indexLigne, colonne: indexColonne })"
                  (keydown)="onCellKeydown($event, indexLigne, indexColonne)"
                  (click)="openCell(indexLigne, indexColonne)"
                  [matTooltip]="cellule.tooltip"
                  [attr.aria-label]="cellule.tooltip"
                >{{ cellule.label }}</td>
              }
            </tr>
          }
        </tbody>
      </table>
    </div>
  `,
  styleUrls: ['../../../styles/heatmap.css', './marge.css'],
  // Global by design (AGENTS.md): loaded with the route that shows the tab.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MarginBeforeGrid {
  /** `GET /api/marge?mode=avant`, read by the tab that hosts the grid. */
  readonly rapport = input<RapportMarge | null>(null);

  private readonly router = inject(Router);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  protected readonly table = computed<TableMarge>(() => buildTable(this.rapport()));

  /** The cell holding the focus (roving tabindex), clamped to the grid on screen. */
  protected readonly focusedCell = signal({ ligne: 0, colonne: 0 });
  private readonly focusedPosition = computed(() => {
    const lignes = this.table().lignes;
    const { ligne, colonne } = this.focusedCell();
    const row = Math.min(Math.max(ligne, 0), Math.max(lignes.length - 1, 0));
    const lastColumn = Math.max((lignes[row]?.cellules.length ?? 1) - 1, 0);
    return { ligne: row, colonne: Math.min(Math.max(colonne, 0), lastColumn) };
  });

  protected isFocusedCell(ligne: number, colonne: number): boolean {
    const courante = this.focusedPosition();
    return courante.ligne === ligne && courante.colonne === colonne;
  }

  /** The openings of the cell's day; a cell holding no seat leads nowhere. */
  protected openCell(ligne: number, colonne: number): void {
    const cellule = this.table().lignes[ligne]?.cellules[colonne];
    const lien = cellule ? lienCellule('AVANT', cellule.creneauId, cellule.date) : null;
    if (lien) {
      void this.router.navigate([lien.route], { queryParams: lien.queryParams });
    }
  }

  protected onCellKeydown(event: KeyboardEvent, ligne: number, colonne: number): void {
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      this.openCell(ligne, colonne);
      return;
    }
    const lignes = this.table().lignes;
    const target = nextGridCell(
      event.key,
      { ligne, colonne },
      lignes.length - 1,
      (lignes[ligne]?.cellules.length ?? 1) - 1,
    );
    if (!target) {
      return;
    }
    event.preventDefault();
    this.focusedCell.set(target);
    this.host.nativeElement
      .querySelector<HTMLElement>(
        `[data-ligne="${target.ligne}"][data-colonne="${target.colonne}"]`,
      )
      ?.focus();
  }
}
