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
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Router, RouterLink } from '@angular/router';
import { AnalysesApi } from '../../core/api/analyses-api';
import { PlanningApi } from '../../core/api/planning-api';
import { nextGridCell } from '../../core/grid-navigation';
import { CelluleTension, MotifTension, RapportTension } from '../../core/models';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { errorText } from '../../core/resource-state';
import { StatusMessage } from '../../shared/status-message';
import { libelleTranche, signe } from './marge';
import {
  buildSyntheseTension,
  buildTableTension,
  libelleGravite,
  libelleMotif,
  SyntheseTension,
  TableTension,
} from './tension';

/**
 * The Diagnostic's Tension tab: day × timeslot, what is left once the plan is
 * solved — the people really free minus the seats left empty — crossed with
 * the fragility of the same plan. The former Marge disponible screen read it
 * twice, « après » and « tension »; the second held the first, so they are one.
 *
 * <p>The columns are the grid's start hours, one per evening however late it
 * ends. A cell is coloured by its margin's sign and marked by its grade, and
 * opens the Siège panel of its timeslot on the Journée — the empty seats, the
 * fragile ones, who could come. The worst timeslot of each day opens its
 * reasons beside the grid.</p>
 */
@Component({
  selector: 'app-tension-tab',
  imports: [
    RouterLink,
    MatButtonModule,
    MatCardModule,
    MatIconModule,
    MatProgressBarModule,
    MatTooltipModule,
    StatusMessage,
  ],
  templateUrl: './tension-tab.html',
  styleUrls: ['../../../styles/heatmap.css', './marge.css'],
  // Global by design (AGENTS.md): loaded with the route, unscoped like the partial it was.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TensionTab {
  private readonly analysesApi = inject(AnalysesApi);
  private readonly planningApi = inject(PlanningApi);
  private readonly store = inject(ReferenceDataStore);
  private readonly router = inject(Router);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  private readonly tensionData = resource({ loader: () => this.analysesApi.tension() });
  /** Whether a plan is persisted: the reading needs one. */
  private readonly persisted = resource({ loader: () => this.planningApi.persistedCount() });
  protected readonly persistedPlan = computed(
    () => !this.persisted.hasValue() || (this.persisted.value()?.assignments ?? 0) > 0,
  );

  protected readonly loading = this.tensionData.isLoading;
  protected readonly error = errorText(this.tensionData);
  protected readonly rapport = computed<RapportTension | null>(() =>
    this.tensionData.hasValue() ? this.tensionData.value() : null,
  );
  protected readonly table = computed<TableTension>(() => buildTableTension(this.rapport()));
  protected readonly synthese = computed<SyntheseTension[]>(() =>
    buildSyntheseTension(this.rapport()),
  );

  /** The timeslot whose reasons the side panel lists; `null` until one is opened. */
  protected readonly detail = signal<CelluleTension | null>(null);

  /** The cell holding the focus (roving tabindex), clamped to the grid on screen. */
  protected readonly focusedCell = signal({ ligne: 0, colonne: 0 });
  private readonly focusedPosition = computed(() => {
    const lignes = this.table().lignes;
    if (lignes.length === 0) {
      return { ligne: 0, colonne: 0 };
    }
    const { ligne, colonne } = this.focusedCell();
    const row = Math.min(Math.max(ligne, 0), lignes.length - 1);
    const lastColumn = Math.max((lignes[row]?.cellules.length ?? 1) - 1, 0);
    return { ligne: row, colonne: Math.min(Math.max(colonne, 0), lastColumn) };
  });

  protected readonly libelleGravite = libelleGravite;

  constructor() {
    // The reasons name people and stands: their labels come from the referential.
    void this.store.reload(['animateurs', 'stands']).catch(() => undefined);
  }

  protected refresh(): void {
    this.tensionData.reload();
    this.persisted.reload();
  }

  protected isFocusedCell(ligne: number, colonne: number): boolean {
    const courante = this.focusedPosition();
    return courante.ligne === ligne && courante.colonne === colonne;
  }

  /**
   * A cell opens the Siège panel of its timeslot on the Journée: the page
   * picks a free seat of it, else the first one, and the panel says who could
   * hold it. A hole leads nowhere.
   */
  protected openCell(ligne: number, colonne: number): void {
    const cellule = this.table().lignes[ligne]?.cellules[colonne]?.cellule;
    if (cellule) {
      void this.router.navigate(['/journee'], { queryParams: { creneau: cellule.creneauId } });
    }
  }

  /** Enter and Space follow the cell, the arrows move inside the grid. */
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

  protected motifLabel(motif: MotifTension, cellule: CelluleTension): string {
    return libelleMotif(motif, cellule);
  }

  protected trancheDetail(cellule: CelluleTension): string {
    return `J${cellule.jour} · ${libelleTranche(cellule.debut, cellule.fin)}`;
  }

  protected signedLabel(marge: number): string {
    return signe(marge);
  }

  /** « Prénom Nom », or the id when the referential does not know it (any more). */
  protected nomAnimateur(id: string): string {
    const animateur = this.store.animateurs().find((candidat) => candidat.id === id);
    return animateur ? `${animateur.prenom ?? ''} ${animateur.nom ?? ''}`.trim() || id : id;
  }

  protected nomStand(id: string): string {
    return this.store.stands().find((stand) => stand.id === id)?.nom || id;
  }

  protected closeDetail(): void {
    this.detail.set(null);
  }
}
