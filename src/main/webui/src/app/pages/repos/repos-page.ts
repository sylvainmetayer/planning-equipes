import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  Injector,
  afterNextRender,
  computed,
  inject,
  signal,
  ViewEncapsulation,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
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
  BarreJour,
  LigneRepos,
  LigneTendue,
  TableauRepos,
  TotalJour,
  buildTableauRepos,
  filtrerLignes,
  histogrammeRepos,
  lignesTendues,
  totauxParJour,
} from './repos';
import { StatusMessage } from '../../shared/status-message';

/**
 * How the event is drawn. `grille` is one column per day, the detail; `frise`
 * is one proportional bar per animateur, which fits any number of days on any
 * screen and is the only one still readable on a month-long edition.
 */
export type VueRepos = 'grille' | 'frise';

/**
 * Whether a worked cell prints its hours. `compact` is the default: the colour
 * already says the state, and the duration is what made a column four times
 * wider than it needed to be — it stays one tooltip away, and `confort` brings
 * it back for the short editions it fits on.
 */
export type DensiteRepos = 'compact' | 'confort';

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
    StatusMessage,
    MatButtonModule,
    MatButtonToggleModule,
    MatCardModule,
    MatCheckboxModule,
    MatIconModule,
    MatProgressBarModule,
    MatTooltipModule,
    TableFilter,
  ],
  templateUrl: './repos-page.html',
  styleUrl: './repos-page.css',
  // Global by design (AGENTS.md): loaded with the route, unscoped like the partial it was.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ReposPage {
  protected readonly loading = signal(false);
  protected readonly error = signal('');
  protected readonly planning = signal<PlanningEvenement | null>(null);
  protected readonly filtre = signal('');
  /** Keeps only the animateurs working every single day of the event. */
  protected readonly sansReposSeulement = signal(false);
  protected readonly vue = signal<VueRepos>('grille');
  protected readonly densite = signal<DensiteRepos>('compact');
  /**
   * `?date=`: the day a « Que faire ? » action opened the grid on — its
   * column is marked, brought into view and holds the grid's tab stop.
   */
  protected readonly jourDemande = signal('');

  private readonly planningState = inject(PlanningStateService);
  private readonly route = inject(ActivatedRoute);
  private readonly hote = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);

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

  /** The column of `?date=`, -1 when none is asked for or the plan has no such day. */
  protected readonly colonneDemandee = computed(() => {
    const date = this.jourDemande();
    return date ? this.tableau().jours.findIndex((jour) => jour.date === date) : -1;
  });

  protected readonly lignesAffichees = computed<LigneRepos[]>(() =>
    filtrerLignes(this.lignes(), this.filtre(), this.sansReposSeulement()),
  );

  /** Footer of the grid, counted over the rows actually displayed. */
  protected readonly totaux = computed<TotalJour[]>(() =>
    totauxParJour(this.tableau().jours, this.lignesAffichees()),
  );

  /** The footer's own figures, given a height, over the same displayed rows. */
  protected readonly histogramme = computed<BarreJour[]>(() =>
    histogrammeRepos(this.tableau().jours, this.lignesAffichees()),
  );

  /** The head of the grid: the few lines chaining the most consecutive worked days. */
  protected readonly tendues = computed<LigneTendue[]>(() => lignesTendues(this.lignesAffichees()));

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
    () =>
      this.filtre().trim() !== '' ||
      this.sansReposSeulement() ||
      this.vue() !== 'grille' ||
      this.densite() !== 'compact' ||
      this.jourDemande() !== '',
  );

  protected readonly animateurColumnLabel = $localize`:@@repos.column.animateur:Animateur`;
  protected readonly vueLabel = $localize`:@@repos.vue.label:Choisir l'affichage`;
  protected readonly densiteLabel = $localize`:@@repos.densite.label:Choisir la densité des cellules`;
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
    // Tolerant on the way in, like every other view param: anything but the
    // one non-default value falls back to the default rather than failing.
    this.vue.set(params.get('vue') === 'frise' ? 'frise' : 'grille');
    this.densite.set(params.get('densite') === 'confort' ? 'confort' : 'compact');
    this.jourDemande.set(params.get('date') ?? '');
    void this.refresh();
    keepViewInQueryParams(() => ({
      q: optionalParam(this.filtre()),
      sansRepos: this.sansReposSeulement() ? '1' : null,
      vue: this.vue() === 'frise' ? 'frise' : null,
      densite: this.densite() === 'confort' ? 'confort' : null,
      date: optionalParam(this.jourDemande()),
    }));
  }

  protected async refresh(): Promise<void> {
    this.loading.set(true);
    this.error.set('');
    try {
      this.planning.set(await this.planningState.loadForDisplay());
      this.showRequestedDay();
    } catch (error) {
      this.planning.set(null);
      this.error.set(errorPrefix(error));
    } finally {
      this.loading.set(false);
    }
  }

  /** Puts the grid's tab stop on the column of `?date=`, and scrolls that column into view. */
  private showRequestedDay(): void {
    const colonne = this.colonneDemandee();
    if (colonne < 0) {
      return;
    }
    this.celluleCourante.set({ ligne: 0, colonne });
    afterNextRender(
      () =>
        this.hote.nativeElement
          .querySelector<HTMLElement>('.repos-colonne-demandee')
          ?.scrollIntoView?.({ block: 'nearest', inline: 'center' }),
      { injector: this.injector },
    );
  }

  /** Back to the view this page opens on: everybody, no search, the compact grid, no day singled out. */
  protected resetView(): void {
    this.jourDemande.set('');
    this.filtre.set('');
    this.sansReposSeulement.set(false);
    this.vue.set('grille');
    this.densite.set('compact');
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
