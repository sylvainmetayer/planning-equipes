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
  DestroyRef,
  ElementRef,
  computed,
  inject,
  input,
  linkedSignal,
  model,
  output,
  ViewEncapsulation,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { AffectationExplanationService } from '../../core/affectation-explanation.service';
import { errorMessage } from '../../core/error-message';
import { NotificationService } from '../../core/notification.service';
import { SolverJobService } from '../../core/solver-job.service';
import { resumeDeplacement } from '../../shared/deplacement';
import { openMoveDialog } from '../../shared/deplacement-dialog';
import { OptionSelection } from '../../shared/selection-recherche';
import { PlanningEvenement, TypologieItem, RapportPauses } from '../../core/models';
import { correspondAuFiltre } from '../../core/text-filter';
import { typologieColorClass, typologieLabel, typologieLabels } from '../../core/typologie-colors';
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
 * x axis, vacations as positioned blocks. The dual of the day calendar, which
 * lists the same day stand by stand — here the gaps, the daily spans and the
 * back-to-back chains show at a glance, and so do the people not working at
 * all, which is what a day of tension needs.
 *
 * One rendering of the Journée page (`pages/journee`), which owns the day, the
 * shared filters and the data: this view reads the plan and the breaks it is
 * handed, draws them through the pure `buildRailJours()`, and only keeps what
 * is its own — which lines it shows. A drop that rewrote the plan asks the
 * page to re-read it rather than fetching by itself.
 */
@Component({
  selector: 'app-rail-jour-vue',
  imports: [
    CdkDrag,
    CdkDragHandle,
    CdkDropList,
    CdkDropListGroup,
    MatButtonModule,
    MatButtonToggleModule,
    MatCardModule,
    MatIconModule,
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
  /** The day number the page selected; the first day of the plan when null. */
  readonly jour = input<number | null>(null);
  /** The page's shared filters: a name, a stand, an animateur — each empty when unset. */
  readonly filtre = input('');
  readonly stand = input('');
  readonly animateur = input('');
  /** Which lines the rail keeps — the one piece of view state this rendering owns. */
  readonly view = model<RailVue>('tous');
  /** The plan moved under this view (a drop): the page re-reads it. */
  readonly rechargement = output<void>();

  private readonly hote = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly dialog = inject(MatDialog);
  private readonly destroyRef = inject(DestroyRef);

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
    await this.transferer(
      event.item.data,
      event.previousContainer.data,
      event.container.data.animateurId,
    );
  }

  /** The move itself, whichever way it was asked for: a drop or the dialog. */
  private async transferer(bloc: RailBloc, porteur: RailLigne, receveurId: string): Promise<void> {
    try {
      // The line the block was dragged from is who this view believes holds
      // the seat: the server refuses (409) if somebody else does now.
      const simulation = await this.explications.deplacer(
        bloc.posteId,
        { animateurId: receveurId },
        porteur.animateurId,
      );
      this.notifications.notify({
        ...resumeDeplacement(simulation, (id) => this.nomDe(id)),
        variant: 'success',
      });
      this.rechargement.emit();
    } catch (error) {
      this.notifications.notify({
        title: $localize`:@@railJour.depotRefuse:Déplacement refusé`,
        message: errorMessage(error),
        variant: 'error',
      });
      // The refusal may be « this seat moved under you »: re-read the day.
      this.rechargement.emit();
    }
  }

  /**
   * The drop's twin, for the keyboard and for a single click: which of the
   * person's shifts, and to whom — every other line the drop would accept —
   * then the very {@link transferer} the drop calls.
   */
  protected openMove(porteur: RailLigne, bloc?: RailBloc): void {
    if (this.editingLocked()) {
      return;
    }
    const cibles: OptionSelection[] = this.lignes()
      .filter((ligne) => ligne !== porteur && ligne.statut !== 'indisponible')
      .map((ligne) => ({ id: ligne.animateurId, label: ligne.nom }));
    const blocs = bloc ? [bloc] : porteur.blocs;
    openMoveDialog(this.dialog, {
      title: $localize`:@@railJour.deplacer.titre:Déplacer une vacation de ${porteur.nom}:nom:`,
      sources: blocs.map((candidat) => ({ id: candidat.posteId, label: candidat.label })),
      targets: cibles,
      targetLabel: $localize`:@@railJour.deplacer.cible:Vers qui ? Elle prend la vacation, ou échange la sienne`,
    })
      .afterClosed()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((choix) => {
        const choisi = blocs.find((candidat) => candidat.posteId === choix?.source);
        if (choix && choisi) {
          void this.transferer(choisi, porteur, choix.target);
        }
      });
  }

  private nomDe(animateurId: string): string {
    return this.lignes().find((ligne) => ligne.animateurId === animateurId)?.nom ?? animateurId;
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
    // Enter on a line is the keyboard twin of dragging one of its shifts
    // (RGAA 7.3): the drag has no key of its own.
    if (event.key === 'Enter') {
      const ligne = this.lignesAffichees()[index];
      if (ligne && ligne.blocs.length > 0) {
        event.preventDefault();
        this.openMove(ligne);
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
