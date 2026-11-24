import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  computed,
  inject,
  input,
  linkedSignal,
  model,
  output,
  ViewEncapsulation,
} from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { NotificationService } from '../../core/notification.service';
import {
  PlanningEvenement,
  GroupedArrivalReport,
  WalkSequenceReport,
  RapportPauses,
  TypologieItem,
} from '../../core/models';
import { correspondAuFiltre } from '../../core/text-filter';
import { typologieColorClass, typologieLabel, typologieLabels } from '../../core/typologie-colors';
import {
  RailBloc,
  RailJour,
  RailLigne,
  buildRailJours,
  compterStatuts,
  shiftToOpen,
} from './rail-jour';
import { RouterLink } from '@angular/router';

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
 * x axis, vacations as positioned blocks. The dual of the day calendar, which
 * lists the same day stand by stand — here the gaps, the daily spans and the
 * back-to-back chains show at a glance, and so do the people not working at
 * all, which is what a day of tension needs.
 *
 * One rendering of the Journée page (`pages/journee`), which owns the day, the
 * shared filters and the data: this view reads the plan and the breaks it is
 * handed, draws them through the pure `buildRailJours()`, and only keeps what
 * is its own — which lines it shows. It never fetches by itself.
 */
@Component({
  selector: 'app-rail-jour-vue',
  imports: [
    MatButtonModule,
    MatButtonToggleModule,
    MatCardModule,
    MatIconModule,
    NgTemplateOutlet,
    RouterLink,
  ],
  templateUrl: './rail-jour-vue.html',
  styleUrl: './rail-jour-vue.css',
  // Global by design (AGENTS.md): loaded with the route, unscoped like the partial it was.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RailJourView {
  readonly planning = input<PlanningEvenement | null>(null);
  /** Typologie referential, only used to turn ids into legend labels. */
  readonly typologies = input<TypologieItem[]>([]);
  /** The breaks of the plan; null when the request failed — the rail still draws. */
  readonly pauses = input<RapportPauses | null>(null);
  /** The tight walks of the plan; null when the request failed — the rail still draws. */
  readonly walks = input<WalkSequenceReport | null>(null);
  /** The grouped arrivals of the plan; null when the request failed — the rail still draws. */
  readonly groupedArrivals = input<GroupedArrivalReport | null>(null);
  /** The day number the page selected; the first day of the plan when null. */
  readonly jour = input<number | null>(null);
  /** The page's shared filters: a name, a stand, an animateur — each empty when unset. */
  readonly filtre = input('');
  readonly stand = input('');
  readonly animateur = input('');
  /** Which lines the rail keeps — the one piece of view state this rendering owns. */
  readonly view = model<RailVue>('tous');
  /** The seat the page's Siège panel is open on, marked on screen; null when it is closed. */
  readonly openSeatId = input<string | null>(null);
  /** A shift was clicked: the page opens the Siège panel on its seat. */
  readonly seatSelected = output<string>();

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
      this.walks(),
      this.groupedArrivals(),
    );
  });

  /** Day the rail shows: the one the page selected, else the first of the plan. */
  protected readonly jourCourant = computed<RailJour | null>(() => {
    const jours = this.jours();
    return jours.find((candidat) => candidat.jour === this.jour()) ?? jours[0] ?? null;
  });

  protected readonly lignes = computed<RailLigne[]>(() => this.jourCourant()?.lignes ?? []);

  protected readonly lignesAffichees = computed<RailLigne[]>(() => {
    const view = this.view();
    const filtre = this.filtre();
    const stand = this.stand();
    const animateur = this.animateur();
    return this.lignes().filter((ligne) => {
      if (view === 'libres' && ligne.statut !== 'libre') {
        return false;
      }
      if (view === 'affectes' && ligne.statut !== 'affecte') {
        return false;
      }
      if (animateur && ligne.animateurId !== animateur) {
        return false;
      }
      if (stand && !ligne.blocs.some((bloc) => bloc.standId === stand)) {
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

  /**
   * The line the grid hands the focus to (roving tabindex): one stop for the
   * whole rail on Tab, then the arrows walk the lines. Clamped on read against
   * the displayed lines — filtering out the line it pointed at would otherwise
   * leave no cell in the tab order at all.
   */
  private readonly ligneFocus = linkedSignal<number | null, number>({
    // A new day puts the focus back on its first line.
    source: this.jour,
    computation: () => 0,
  });

  protected readonly ligneCourante = computed(() => {
    const lignes = this.lignesAffichees();
    if (lignes.length === 0) {
      return 0;
    }
    return Math.min(Math.max(this.ligneFocus(), 0), lignes.length - 1);
  });

  protected readonly animateurColumnLabel = $localize`:@@railJour.column.animateur:Animateur`;

  private readonly notifications = inject(NotificationService);

  /** A shift was clicked: its Siège panel, on the page. */
  protected openSeat(bloc: RailBloc): void {
    this.seatSelected.emit(bloc.posteId);
  }
  protected readonly libreLabel = $localize`:@@railJour.statut.libre:Libre`;
  protected readonly indisponibleLabel = $localize`:@@railJour.statut.indisponible:Indisponible`;
  protected readonly chevauchementLabel = $localize`:@@railJour.chevauchement:Vacations qui se chevauchent : cet animateur est attendu à deux endroits en même temps`;

  /** Reads the `lignes` query param the page hands over at construction. */
  static readLignes(value: string | null): RailVue {
    return value === 'libres' || value === 'affectes' ? value : 'tous';
  }

  /** Keeps the roving tabindex on the line the user actually reached, mouse or keyboard. */
  protected focusLigne(index: number): void {
    this.ligneFocus.set(index);
  }

  protected naviguer(event: KeyboardEvent, index: number): void {
    // Space on a line is the keyboard twin of a click on one of its shifts:
    // the label is out of the tab order, inside a track hidden from
    // assistive technology, so the line is the one way in.
    if (event.key === ' ') {
      const ligne = this.lignesAffichees()[index];
      if (!ligne) {
        return;
      }
      event.preventDefault();
      const bloc = shiftToOpen(ligne.blocs, this.openSeatId());
      if (bloc) {
        this.openSeat(bloc);
      } else {
        this.notifications.notify({
          title: $localize`:@@railJour.siege.aucuneVacation:Aucune vacation sur cette ligne : pas de siège à ouvrir.`,
          variant: 'info',
        });
      }
      return;
    }
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
