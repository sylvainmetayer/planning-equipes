import {
  CdkDrag,
  CdkDragDrop,
  CdkDragHandle,
  CdkDropList,
  CdkDropListGroup,
} from '@angular/cdk/drag-drop';
import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  computed,
  inject,
  signal,
  ViewEncapsulation,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { ActivatedRoute } from '@angular/router';
import { AffectationExplanationService } from '../../core/affectation-explanation.service';
import { AnalysesApi } from '../../core/api/analyses-api';
import { errorMessage } from '../../core/error-message';
import { NotificationService } from '../../core/notification.service';
import { PlanningStateService } from '../../core/planning-state.service';
import { SolverJobService } from '../../core/solver-job.service';
import { resumeDeplacement } from '../../shared/deplacement';
import { PlanningEvenement, TypologieItem, RapportPauses } from '../../core/models';
import { correspondAuFiltre } from '../../core/text-filter';
import { typologieColorClass, typologieLabel, typologieLabels } from '../../core/typologie-colors';
import { errorPrefix } from '../../core/error-message';
import { dayNavigation, dayNumberParam } from '../../core/day-navigation';
import { keepViewInQueryParams, optionalParam } from '../../core/view-query-params';
import { TableFilter } from '../../shared/table-filter';
import { RailBloc, RailJour, RailLigne, buildRailJours, compterStatuts } from './rail-jour';

/** Which lines the rail keeps: everyone, only the mobilisable ones, only the working ones. */
export type RailVue = 'tous' | 'libres' | 'affectes';

/** One entry of the typologie colour legend shown above the rail. */
interface RailLegendItem {
  id: string;
  label: string;
  colorClass: string;
}

/**
 * Day "rail" (issue #305): one line per animateur of the edition, time on the
 * x axis, vacations as positioned blocks. The dual of `calendar-day-page.ts`,
 * which lists the same day stand by stand — here the gaps, the daily spans and
 * the back-to-back chains show at a glance, and so do the people not working
 * at all, which is what a day of tension needs.
 *
 * Same read-only source and pure-builder pattern as the other views
 * (`planningState.loadForDisplay()` + `buildRailJours()`): no dedicated
 * endpoint, and never a solve.
 */
@Component({
  selector: 'app-rail-jour-page',
  imports: [
    CdkDrag,
    CdkDragHandle,
    CdkDropList,
    CdkDropListGroup,
    MatButtonModule,
    MatButtonToggleModule,
    MatCardModule,
    MatFormFieldModule,
    MatIconModule,
    MatProgressBarModule,
    MatSelectModule,
    TableFilter,
  ],
  templateUrl: './rail-jour-page.html',
  styleUrl: './rail-jour-page.css',
  // Global by design (AGENTS.md): loaded with the route, unscoped like the partial it was.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RailJourPage {
  protected readonly loading = signal(false);
  protected readonly error = signal('');
  protected readonly planning = signal<PlanningEvenement | null>(null);
  /** Typologie referential, only used to turn ids into legend labels. */
  protected readonly typologies = signal<TypologieItem[]>([]);
  /** The breaks of the plan; null when the request failed — the rail still draws. */
  protected readonly pauses = signal<RapportPauses | null>(null);
  protected readonly filtre = signal('');
  protected readonly view = signal<RailVue>('tous');

  private readonly analysesApi = inject(AnalysesApi);
  private readonly planningState = inject(PlanningStateService);
  private readonly route = inject(ActivatedRoute);
  private readonly hote = inject<ElementRef<HTMLElement>>(ElementRef);

  protected readonly jours = computed<RailJour[]>(() => {
    const planning = this.planning();
    if (!planning) {
      return [];
    }
    // The ad hoc exceptions travel with the plan already: no second request to
    // know which hours someone was recorded as unavailable on.
    return buildRailJours(
      planning.postes ?? [],
      planning.animateurs ?? [],
      planning.contraintesAdHoc ?? [],
      this.pauses(),
    );
  });

  /** Day the rail shows: the one asked for, else the first of the event. A new day puts the focus back on its first line. */
  private readonly navigation = dayNavigation(this.jours, (jour) => jour.jour, {
    initial: dayNumberParam(this.route.snapshot.queryParamMap.get('jour')),
    onSelect: () => this.ligneFocus.set(0),
  });
  protected readonly jourCourant = this.navigation.current;
  protected readonly estPremierJour = this.navigation.isFirst;
  protected readonly estDernierJour = this.navigation.isLast;

  protected readonly lignes = computed<RailLigne[]>(() => this.jourCourant()?.lignes ?? []);

  protected readonly lignesAffichees = computed<RailLigne[]>(() => {
    const view = this.view();
    const filtre = this.filtre();
    return this.lignes().filter((ligne) => {
      if (view === 'libres' && ligne.statut !== 'libre') {
        return false;
      }
      if (view === 'affectes' && ligne.statut !== 'affecte') {
        return false;
      }
      return correspondAuFiltre(filtre, [ligne.nom]);
    });
  });

  protected readonly compteurs = computed(() => compterStatuts(this.lignes()));

  protected readonly compteursLabel = computed(() => {
    const { affectes, libres, indisponibles } = this.compteurs();
    const total = this.lignes().length;
    return $localize`:@@railJour.counters:${total}:total: animateur(s) : ${affectes}:affectes: affecté(s), ${libres}:libres: mobilisable(s), ${indisponibles}:indisponibles: indisponible(s)`;
  });

  /** Typologies actually present on the displayed day, so the legend only explains colours that are on screen. */
  protected readonly legende = computed<RailLegendItem[]>(() => {
    const labels = typologieLabels(this.typologies());
    const ids = new Set<string>();
    this.lignes().forEach((ligne) =>
      ligne.blocs.forEach((bloc) => {
        if (bloc.typologie) {
          ids.add(bloc.typologie);
        }
      }),
    );
    return Array.from(ids)
      .map((id) => ({ id, label: typologieLabel(labels, id), colorClass: typologieColorClass(id) }))
      .sort((left, right) => left.label.localeCompare(right.label));
  });

  /**
   * Background step of one line's track: the hour gridlines for a normal line,
   * nothing for a hatched one.
   *
   * An unavailable line replaces the gridlines with a 45° hatching, which an
   * hour-wide `background-size` would tile once per hour — a visible seam every
   * hour instead of one continuous pattern. And an inline style wins over any
   * stylesheet rule, so the fix has to be here rather than in the CSS.
   */
  protected fondPiste(ligne: RailLigne): string | null {
    return ligne.statut === 'indisponible' ? null : this.gridSize();
  }

  /**
   * Width of one hour of the rail, as a background-size: the gridlines are one
   * repeating background instead of one element per hour and per line — at 150
   * lines and a fifteen-hour day that would be 2 250 divs drawing nothing.
   */
  protected readonly gridSize = computed(() => {
    const jour = this.jourCourant();
    if (!jour) {
      return '100% 100%';
    }
    const heures = Math.max(1, (jour.finMinutes - jour.debutMinutes) / 60);
    return `${100 / heures}% 100%`;
  });

  /** True as soon as the filters differ from the ones this page opens on — the day itself is navigation, not a filter. */
  protected readonly viewChanged = computed(
    () => this.view() !== 'tous' || this.filtre().trim() !== '',
  );

  /**
   * The line the grid hands the focus to (roving tabindex): one stop for the
   * whole rail on Tab, then the arrows walk the lines. Clamped on read against
   * the displayed lines — filtering out the line it pointed at would otherwise
   * leave no cell in the tab order at all.
   */
  private readonly ligneFocus = signal(0);

  protected readonly ligneCourante = computed(() => {
    const lignes = this.lignesAffichees();
    if (lignes.length === 0) {
      return 0;
    }
    return Math.min(Math.max(this.ligneFocus(), 0), lignes.length - 1);
  });

  protected readonly animateurColumnLabel = $localize`:@@railJour.column.animateur:Animateur`;
  protected readonly glisserTooltip = $localize`:@@railJour.glisser:Glisser vers une autre personne : elle prend cette vacation, ou échange la sienne si elle travaille déjà à cette heure. Refusé si une règle dure serait cassée.`;

  /* ----------------------------- Glisser-déposer (#308) ----------------------------- */

  private readonly explications = inject(AffectationExplanationService);
  private readonly notifications = inject(NotificationService);
  /** A drop is a write to the plan: locked, like every other, while a solve is rewriting it. */
  protected readonly editingLocked = inject(SolverJobService).editingLocked;

  /** Any other person's line receives, whether they work at that hour (swap) or not (hand-over). */
  protected readonly peutRecevoir = (
    drag: CdkDrag<RailBloc>,
    drop: CdkDropList<RailLigne>,
  ): boolean => drag.dropContainer !== drop && drop.data.statut !== 'indisponible';

  /**
   * A vacation dropped on another person's line: that person takes the seat,
   * or — when they already hold one on the same créneau — the two swap. The
   * server decides which, simulates on the persisted plan and refuses a drop
   * that would break a hard rule, naming it.
   */
  protected async onDrop(event: CdkDragDrop<RailLigne, RailLigne, RailBloc>): Promise<void> {
    if (event.previousContainer === event.container) {
      return;
    }
    const bloc = event.item.data;
    const receveur = event.container.data;
    const porteur = event.previousContainer.data;
    try {
      // The line the block was dragged from is who this view believes holds
      // the seat: the server refuses (409) if somebody else does now.
      const simulation = await this.explications.deplacer(
        bloc.posteId,
        { animateurId: receveur.animateurId },
        porteur.animateurId,
      );
      this.notifications.notify({
        ...resumeDeplacement(simulation, (id) => this.nomDe(id)),
        variant: 'success',
      });
      this.planningState.set(null);
      await this.refresh();
    } catch (error) {
      this.notifications.notify({
        title: $localize`:@@railJour.depotRefuse:Déplacement refusé`,
        message: errorMessage(error),
        variant: 'error',
      });
      // The refusal may be « this seat moved under you »: re-read the day.
      this.planningState.set(null);
      await this.refresh();
    }
  }

  private nomDe(animateurId: string): string {
    return this.lignes().find((ligne) => ligne.animateurId === animateurId)?.nom ?? animateurId;
  }
  protected readonly libreLabel = $localize`:@@railJour.statut.libre:Libre`;
  protected readonly indisponibleLabel = $localize`:@@railJour.statut.indisponible:Indisponible`;
  protected readonly chevauchementLabel = $localize`:@@railJour.chevauchement:Vacations qui se chevauchent : cet animateur est attendu à deux endroits en même temps`;
  protected readonly jourPrecedentLabel = $localize`:@@railJour.previousDay:Jour précédent`;
  protected readonly jourSuivantLabel = $localize`:@@railJour.nextDay:Jour suivant`;

  constructor() {
    const params = this.route.snapshot.queryParamMap;
    this.filtre.set(params.get('q') ?? '');
    const view = params.get('vue');
    this.view.set(view === 'libres' || view === 'affectes' ? view : 'tous');
    void this.refresh();
    keepViewInQueryParams(() => ({
      jour: this.navigation.queryParam(),
      q: optionalParam(this.filtre()),
      vue: this.view() === 'tous' ? null : this.view(),
    }));
  }

  protected async refresh(): Promise<void> {
    this.loading.set(true);
    this.error.set('');
    try {
      const [planning, typologies, pauses] = await Promise.all([
        this.planningState.loadForDisplay(),
        // Labels only: a missing referential degrades the legend to raw ids
        // rather than failing the rail.
        this.analysesApi.typologies().catch(() => []),
        this.analysesApi.breaks().catch(() => null),
      ]);
      this.planning.set(planning);
      this.typologies.set(typologies);
      this.pauses.set(pauses && typeof pauses === 'object' && 'journees' in pauses ? pauses : null);
    } catch (error) {
      this.planning.set(null);
      this.error.set(errorPrefix(error));
    } finally {
      this.loading.set(false);
    }
  }

  protected selectionnerJour(jour: number): void {
    this.navigation.select(jour);
  }

  protected decalerJour(delta: number): void {
    this.navigation.step(delta);
  }

  protected reinitialiserVue(): void {
    this.view.set('tous');
    this.filtre.set('');
  }

  /** Keeps the roving tabindex on the line the user actually reached, mouse or keyboard. */
  protected focusLigne(index: number): void {
    this.ligneFocus.set(index);
  }

  protected naviguer(event: KeyboardEvent, index: number): void {
    const last = this.lignesAffichees().length - 1;
    let target: number;
    switch (event.key) {
      case 'ArrowDown':
        target = Math.min(index + 1, last);
        break;
      case 'ArrowUp':
        target = Math.max(index - 1, 0);
        break;
      case 'Home':
        target = 0;
        break;
      case 'End':
        target = last;
        break;
      default:
        return;
    }
    event.preventDefault();
    this.ligneFocus.set(target);
    this.hote.nativeElement.querySelector<HTMLElement>(`[data-ligne="${target}"]`)?.focus();
  }
}
