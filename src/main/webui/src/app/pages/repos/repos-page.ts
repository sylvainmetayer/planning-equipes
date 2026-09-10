import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  computed,
  inject,
  signal,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ActivatedRoute } from '@angular/router';
import { PlanningEvenement } from '../../core/models';
import { PlanningStateService } from '../../core/planning-state.service';
import { errorPrefix } from '../../core/error-message';
import { keepViewInQueryParams, optionalParam } from '../../core/view-query-params';
import { TableFilter } from '../../shared/table-filter';
import {
  LigneRepos,
  TableauRepos,
  TotalJour,
  buildTableauRepos,
  filtrerLignes,
  totauxParJour,
} from './repos';

/**
 * Rest days: one line per animateur, one column per day of the event, each
 * cell saying whether the person works that day, rests, or was never available
 * for it.
 *
 * The complement of the day rail, which shows one day at a time and cannot
 * tell whether somebody has been on every single one of them. Here the whole
 * event is on screen at once, so an animateur working eight days in a row —
 * the case the weekly-rest rules exist for — is a full line at a glance.
 *
 * Same read-only source and pure-builder pattern as the other views
 * (`planningState.loadForDisplay()` + `buildTableauRepos()`): no dedicated
 * endpoint, and never a solve.
 */
@Component({
  selector: 'app-repos-page',
  imports: [
    MatButtonModule,
    MatCardModule,
    MatCheckboxModule,
    MatIconModule,
    MatProgressBarModule,
    MatTooltipModule,
    TableFilter,
  ],
  templateUrl: './repos-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ReposPage {
  protected readonly loading = signal(false);
  protected readonly error = signal('');
  protected readonly planning = signal<PlanningEvenement | null>(null);
  protected readonly filtre = signal('');
  /** Keeps only the animateurs working every single day of the event. */
  protected readonly sansReposSeulement = signal(false);

  private readonly planningState = inject(PlanningStateService);
  private readonly route = inject(ActivatedRoute);
  private readonly hote = inject<ElementRef<HTMLElement>>(ElementRef);

  protected readonly tableau = computed<TableauRepos>(() => {
    const planning = this.planning();
    if (!planning) {
      return { jours: [], lignes: [] };
    }
    // The ad hoc exceptions travel with the plan already: no second request to
    // know who was kept off the whole event.
    return buildTableauRepos(
      planning.postes ?? [],
      planning.animateurs ?? [],
      planning.contraintesAdHoc ?? [],
    );
  });

  protected readonly lignes = computed<LigneRepos[]>(() => this.tableau().lignes);

  protected readonly lignesAffichees = computed<LigneRepos[]>(() =>
    filtrerLignes(this.lignes(), this.filtre(), this.sansReposSeulement()),
  );

  /** Footer of the grid, counted over the rows actually displayed. */
  protected readonly totaux = computed<TotalJour[]>(() =>
    totauxParJour(this.tableau().jours, this.lignesAffichees()),
  );

  /** How many people never get a day off — the number this screen exists to bring down. */
  protected readonly sansReposCount = computed(
    () => this.lignes().filter((ligne) => ligne.sansRepos).length,
  );

  protected readonly compteursLabel = computed(() => {
    const total = this.lignes().length;
    const jours = this.tableau().jours.length;
    const sansRepos = this.sansReposCount();
    return $localize`:@@repos.counters:${total}:total: animateur(s) sur ${jours}:jours: journée(s) — ${sansRepos}:sansRepos: sans aucun jour de repos`;
  });

  /** True as soon as the view differs from the one this page opens on. */
  protected readonly viewChanged = computed(
    () => this.filtre().trim() !== '' || this.sansReposSeulement(),
  );

  protected readonly animateurColumnLabel = $localize`:@@repos.column.animateur:Animateur`;
  protected readonly sansReposLabel = $localize`:@@repos.row.sansRepos:Aucun jour de repos sur tout l'événement`;
  protected readonly conflitLabel = $localize`:@@repos.cell.conflitBadge:Affecté alors que la journée est déclarée indisponible`;

  /**
   * The cell the grid hands the focus to (roving tabindex): one stop for the
   * whole table on Tab, then the arrows move inside it. Same rule as the
   * heatmap — every cell carries a rich label nothing could reach otherwise.
   */
  protected readonly celluleCourante = signal({ ligne: 0, colonne: 0 });

  /**
   * The same position, clamped against the rows actually displayed. Filtering
   * out the row it pointed at would otherwise leave no cell carrying
   * `tabindex="0"`, and the grid would fall out of the tab order until the
   * filter was cleared.
   */
  protected readonly positionCourante = computed(() => {
    const lignes = this.lignesAffichees();
    if (lignes.length === 0) {
      return { ligne: 0, colonne: 0 };
    }
    const { ligne, colonne } = this.celluleCourante();
    const ligneTenue = Math.min(Math.max(ligne, 0), lignes.length - 1);
    const derniereColonne = Math.max((lignes[ligneTenue]?.cellules.length ?? 1) - 1, 0);
    return { ligne: ligneTenue, colonne: Math.min(Math.max(colonne, 0), derniereColonne) };
  });

  constructor() {
    const params = this.route.snapshot.queryParamMap;
    this.filtre.set(params.get('q') ?? '');
    this.sansReposSeulement.set(params.get('sansRepos') === '1');
    void this.refresh();
    keepViewInQueryParams(() => ({
      q: optionalParam(this.filtre()),
      sansRepos: this.sansReposSeulement() ? '1' : null,
    }));
  }

  protected async refresh(): Promise<void> {
    this.loading.set(true);
    this.error.set('');
    try {
      this.planning.set(await this.planningState.loadForDisplay());
    } catch (error) {
      this.planning.set(null);
      this.error.set(errorPrefix(error));
    } finally {
      this.loading.set(false);
    }
  }

  /** Back to the view this page opens on: everybody, no search. */
  protected reinitialiserVue(): void {
    this.filtre.set('');
    this.sansReposSeulement.set(false);
  }

  protected estCelluleCourante(ligne: number, colonne: number): boolean {
    const courante = this.positionCourante();
    return courante.ligne === ligne && courante.colonne === colonne;
  }

  protected naviguer(event: KeyboardEvent, ligne: number, colonne: number): void {
    const lignes = this.lignesAffichees();
    const derniereLigne = lignes.length - 1;
    const derniereColonne = (lignes[ligne]?.cellules.length ?? 1) - 1;
    // Every branch below assigns it, and the default returns.
    let target: { ligne: number; colonne: number };
    switch (event.key) {
      case 'ArrowRight':
        target = { ligne, colonne: Math.min(colonne + 1, derniereColonne) };
        break;
      case 'ArrowLeft':
        target = { ligne, colonne: Math.max(colonne - 1, 0) };
        break;
      case 'ArrowDown':
        target = { ligne: Math.min(ligne + 1, derniereLigne), colonne };
        break;
      case 'ArrowUp':
        target = { ligne: Math.max(ligne - 1, 0), colonne };
        break;
      case 'Home':
        target = { ligne, colonne: 0 };
        break;
      case 'End':
        target = { ligne, colonne: derniereColonne };
        break;
      default:
        return;
    }
    event.preventDefault();
    this.celluleCourante.set(target);
    const selecteur = `[data-ligne="${target.ligne}"][data-colonne="${target.colonne}"]`;
    this.hote.nativeElement.querySelector<HTMLElement>(selecteur)?.focus();
  }
}
