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
  computed,
  inject,
  input,
  model,
  output,
  ViewEncapsulation,
} from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink } from '@angular/router';
import { injectAppConfig } from '../../core/app-config';
import { AffectationExplanationService } from '../../core/affectation-explanation.service';
import { errorMessage } from '../../core/error-message';
import { NotificationService } from '../../core/notification.service';
import { SolverJobService } from '../../core/solver-job.service';
import { cibleDepot, moveTargets, resumeDeplacement } from '../../shared/deplacement';
import { openMoveDialog } from '../../shared/deplacement-dialog';
import { VerrouillageStore } from '../../core/verrouillage.store';
import { Creneau, PlanningEvenement, PosteAffectation, Stand } from '../../core/models';
import { aUneAppreciationPour } from '../../shared/affectation-explanation-dialog';
import { correspondAuFiltre } from '../../core/text-filter';
import { formatHeure } from '../../core/time-of-day';

interface AssignedEntry {
  poste: PosteAffectation;
  label: string;
}

interface StandLine {
  /** Identity of the line: two distinct stands may well share the same name. */
  standId: string;
  standNom: string;
  /** The place the stand stands on, printed under its name; null when it has none. */
  emplacementNom: string | null;
  /**
   * The window actually staffed by `entries` — the poste's effective window if
   * a partial stand closure (issue #60) narrowed it, otherwise the créneau's
   * own hours. Two segments of the same stand and créneau (one on each side
   * of a mid-créneau closure) become two separate lines, each with its own
   * window.
   */
  heureDebut: string;
  heureFin: string;
  /** One per filled seat; unfilled seats are only reflected by this being empty (see `unassignedLabel`). */
  entries: AssignedEntry[];
  /** The seats nobody holds, each a drop target of its own (issue #308). */
  postesLibres: PosteAffectation[];
  /**
   * Seats actually generated for this line — the number of `PosteAffectation`
   * it holds, filled or not — and therefore the headcount understaffing is
   * measured against.
   *
   * <p>Not `stand.effectifMin`: a vacation covering a meal pause under the
   * `EFFECTIF_REDUIT` strategy is deliberately staffed at half the stand's
   * usual headcount (see {@code PlanningService#construirePostes}), so
   * comparing it to `effectifMin` reported every such slot as understaffed
   * when it was in fact exactly as staffed as intended.</p>
   */
  effectifRequis: number;
  /** True when this line is a meal-pause coverage vacation: a deliberately reduced headcount, flagged as information, never as a shortfall. */
  couverturePause: boolean;
}

interface SlotCard {
  creneauId: number;
  heureDebut: string;
  heureFin: string;
  stands: StandLine[];
}

interface DayCard {
  jour: number;
  /** ISO date of the day, to match a JOUR lock; null when the créneaux carry none. */
  date: string | null;
  title: string;
  slots: SlotCard[];
  /** True when any stand-line on this day has some, but fewer than effectifMin, animateurs — shown on the card header, at a glance. */
  understaffed: boolean;
  /** True when any filled seat this day lacks the administrator's appreciation for its stand — shown on the card header, at a glance. */
  appreciationMismatch: boolean;
}

/**
 * What the seat chips of the relecture bar narrow the table to: every line,
 * the lines with a seat nobody holds, or the lines a lock freezes.
 */
export type FiltreSieges = 'tous' | 'vides' | 'verrous';

/** Reads the `sieges` query param; anything unknown is every line. */
export function readFiltreSieges(value: string | null): FiltreSieges {
  return value === 'vides' || value === 'verrous' ? value : 'tous';
}

/** One column of the table: a timeslot of the day, its hours without seconds. */
export interface ColonneTableau {
  slot: SlotCard;
  /** `09:00–12:00`. */
  label: string;
}

/**
 * One cell: what a stand does in one timeslot — its lines (usually one; two
 * when a partial closure cut the timeslot in two), or none when the stand
 * does not open then, which the table says in a neutral « fermé ».
 */
export interface CelluleTableau {
  slot: SlotCard;
  lignes: StandLine[];
}

/** One row: a stand, and its cells in the columns' order. */
export interface LigneTableau {
  standId: string;
  standNom: string;
  emplacementNom: string | null;
  cellules: CelluleTableau[];
}

/** The day as a table: stands down, timeslots across. */
export interface TableauJour {
  day: DayCard;
  colonnes: ColonneTableau[];
  lignes: LigneTableau[];
}

/**
 * Pivots one day — built slot by slot — into stands × timeslots, the way the
 * day is read on a wall: one row per stand, alphabetically, a column per
 * timeslot. A row is kept when `garde` accepts one of its lines, and then
 * kept whole: the other timeslot of a stand with an empty seat is the
 * context the reader needs to fill it.
 */
export function tableauJour(day: DayCard, garde: (ligne: StandLine) => boolean): TableauJour {
  const colonnes = day.slots.map((slot) => ({
    slot,
    label: `${formatHeure(slot.heureDebut)}–${formatHeure(slot.heureFin)}`,
  }));
  const stands = new Map<string, { nom: string; emplacementNom: string | null }>();
  for (const slot of day.slots) {
    for (const ligne of slot.stands) {
      if (!stands.has(ligne.standId)) {
        stands.set(ligne.standId, { nom: ligne.standNom, emplacementNom: ligne.emplacementNom });
      }
    }
  }
  const lignes: LigneTableau[] = [];
  for (const [standId, stand] of stands) {
    const cellules = day.slots.map((slot) => ({
      slot,
      lignes: slot.stands.filter((ligne) => ligne.standId === standId),
    }));
    if (cellules.some((cellule) => cellule.lignes.some(garde))) {
      lignes.push({ standId, standNom: stand.nom, emplacementNom: stand.emplacementNom, cellules });
    }
  }
  lignes.sort(
    (gauche, droite) =>
      gauche.standNom.localeCompare(droite.standNom) ||
      gauche.standId.localeCompare(droite.standId),
  );
  return { day, colonnes, lignes };
}

/**
 * The day as a table, stands × timeslots, the whole width of the screen: one
 * row per stand with its location under its name, one name per chip, the
 * seats nobody holds in red, a stand shut for a timeslot in a neutral « fermé »
 * and a partial opening as a plain window, never as an alarm.
 *
 * One rendering of the Planning page's « Par jour » axis (`pages/journee`),
 * which owns the day, the shared filters and the plan: this view draws the
 * day it is handed through the pure `buildDays()` and `tableauJour()`, and
 * keeps only its own switches — problem lines only, and the seat filter of
 * the relecture chips. A drop or a repair that rewrote the plan asks the page
 * to re-read it rather than fetching by itself.
 */
@Component({
  selector: 'app-calendar-day-vue',
  imports: [
    CdkDrag,
    CdkDragHandle,
    CdkDropList,
    CdkDropListGroup,
    FormsModule,
    NgTemplateOutlet,
    MatCheckboxModule,
    MatIconModule,
    MatTooltipModule,
    RouterLink,
  ],
  templateUrl: './calendar-day-vue.html',
  styleUrl: '../../../styles/calendar-day.css',
  // Global by design (AGENTS.md): loaded with the route, unscoped like the partial it was.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CalendarDayView {
  readonly planning = input<PlanningEvenement | null>(null);
  /** The day number the page selected; the first day of the plan when null. */
  readonly jour = input<number | null>(null);
  /** The page's shared filters: a text, a stand id, an animateur id — each empty when unset. */
  readonly filtre = input('');
  readonly stand = input('');
  readonly animateur = input('');
  /**
   * The stands the location and game-category filters leave, null when
   * neither is set: the page resolves them once for every rendering.
   */
  readonly standsRetenus = input<ReadonlySet<string> | null>(null);
  /** The relecture chips' filter: the lines with an empty seat, or those a lock freezes. */
  readonly sieges = model<FiltreSieges>('tous');
  /**
   * Narrows the day to the stand lines that need attention — the view state
   * this rendering owns (`problemes`). An event day holds dozens of lines of
   * which two are wrong; scrolling all of them to find those two is the actual
   * daily task this screen exists for.
   */
  readonly seulementProblemes = model(false);
  /** The plan moved under this view (a drop, a repair): the page re-reads it. */
  readonly rechargement = output<void>();
  /** The seat the page's Siège panel is open on, marked on screen; null when it is closed. */
  readonly openSeatId = input<string | null>(null);
  /** A seat was clicked: the page opens its panel on it. */
  readonly seatSelected = output<string>();

  /** Bounds the repair-assistant callback to this view's life: it is lazy and rebuilt on every visit. */
  private readonly destroyRef = inject(DestroyRef);
  protected readonly seatLabel = $localize`:@@calendarDay.siege.ouvrir:Ouvrir ce siège : pourquoi lui, le remplacer, le déplacer, le libérer`;

  protected readonly verrous = inject(VerrouillageStore);

  private readonly dialog = inject(MatDialog);
  private readonly explications = inject(AffectationExplanationService);
  private readonly notifications = inject(NotificationService);
  /** A drop is a write to the plan: locked, like every other, while a solve is rewriting it. */
  protected readonly editingLocked = inject(SolverJobService).editingLocked;
  /**
   * Whether this instance offers the gesture at all (`GLISSER_DEPOSER_ACTIF`,
   * off by default): read from the server's answer once, like `devMode`.
   */
  protected readonly dragDropEnabled = injectAppConfig().dragDropEnabled;

  private readonly toutesLesJournees = computed<DayCard[]>(() =>
    buildDays(this.planning()?.postes ?? []),
  );

  /** The one day on screen: the day the page selected, else the first of the plan. */
  private readonly journee = computed<DayCard | null>(() => {
    const jours = this.toutesLesJournees();
    return jours.find((day) => day.jour === this.jour()) ?? jours[0] ?? null;
  });

  /** The day on screen as a table, narrowed by the page's filters and this view's own. */
  protected readonly tableau = computed<TableauJour | null>(() => {
    const day = this.journee();
    if (!day) {
      return null;
    }
    const filtre = this.filtre();
    const stand = this.stand();
    const animateur = this.animateur();
    const retenus = this.standsRetenus();
    const problemes = this.seulementProblemes();
    const sieges = this.sieges();
    const garde = (ligne: StandLine): boolean =>
      (!stand || ligne.standId === stand) &&
      (!retenus || retenus.has(ligne.standId)) &&
      (!animateur || ligne.entries.some((entry) => entry.poste.animateur?.id === animateur)) &&
      correspondAuFiltre(filtre, [ligne.standNom, ...ligne.entries.map((entry) => entry.label)]) &&
      (!problemes ||
        this.isUnderstaffed(ligne) ||
        this.hasAppreciationMismatch(ligne) ||
        ligne.entries.length === 0) &&
      (sieges !== 'vides' || ligne.postesLibres.length > 0) &&
      (sieges !== 'verrous' ||
        this.isDayLocked(day) ||
        this.verrous.estStandVerrouille(ligne.standId) ||
        ligne.entries.some((entry) => this.verrous.estCreneauVerrouille(entry.poste.creneau?.id)));
    return tableauJour(day, garde);
  });

  constructor() {
    // Fire-and-forget: the padlocks are an indicator, never a reason to fail
    // the calendar the user came to read.
    void this.verrous.reload().catch(() => undefined);
  }

  /** True when the whole day is frozen by a JOUR lock on the edition. */
  protected isDayLocked(day: Pick<DayCard, 'date'>): boolean {
    return this.verrous.estJourVerrouille(day.date);
  }

  /** True when this stand-line is frozen, either by its stand or by its créneau. */
  protected estLigneVerrouillee(slot: SlotCard, stand: StandLine): boolean {
    return (
      this.verrous.estStandVerrouille(stand.standId) ||
      this.verrous.estCreneauVerrouille(slot.creneauId)
    );
  }

  protected readonly verrouilleTooltip = $localize`:@@verrouillages.indicator:Verrouillé : ces affectations ne bougeront plus à la prochaine résolution`;
  protected readonly siegeLibreLabel = $localize`:@@calendarDay.siegeLibre:siège libre`;
  protected readonly fermeLabel = $localize`:@@calendarDay.ferme:fermé`;
  protected readonly ficheStandLabel = $localize`:@@calendarDay.ficheStand:Ouvrir la fiche du stand`;
  protected readonly freeSeatTooltip = $localize`:@@calendarDay.siegeLibre.ouvrir:Ouvrir ce siège : qui peut le tenir, poser un ajustement`;
  protected readonly glisserTooltip = $localize`:@@calendarDay.glisser:Glisser vers un autre stand : sur un siège libre pour y déplacer la personne, sur une personne pour échanger leurs sièges. Refusé si une règle dure serait cassée.`;

  /* ----------------------------- Glisser-déposer (#308) ----------------------------- */

  /**
   * Which lines light up while a name is being dragged: any other line of the
   * day that has somewhere to put it — a free seat, or a person to swap with.
   * Bound as an arrow so the CDK can call it without a receiver.
   */
  protected readonly peutRecevoir = (
    drag: CdkDrag<PosteAffectation>,
    drop: CdkDropList<StandLine>,
  ): boolean =>
    drag.dropContainer !== drop &&
    (drop.data.postesLibres.length > 0 || drop.data.entries.length > 0);

  /**
   * A name dropped on another line: the seat under the pointer decides
   * between a move (free seat) and a swap (held seat); a line with no seat
   * under the pointer takes its first free one. The server simulates the
   * result on the persisted plan and refuses it when a hard rule would break,
   * naming the rule — which is what the snack bar then shows.
   */
  protected async onDrop(
    event: CdkDragDrop<StandLine, StandLine, PosteAffectation>,
  ): Promise<void> {
    if (event.previousContainer === event.container) {
      return;
    }
    const source = event.item.data;
    const ligne = event.container.data;
    const sousLePointeur = document.elementFromPoint(event.dropPoint.x, event.dropPoint.y);
    const target = cibleDepot(
      sousLePointeur,
      ligne.postesLibres,
      ligne.entries.map((entry) => entry.poste),
    );
    if (!target) {
      this.notifications.notify({
        title: $localize`:@@calendarDay.depotSansSiege:Aucun siège libre sur cette ligne : déposez sur une personne pour échanger.`,
        variant: 'warning',
      });
      return;
    }
    await this.deplacer(source.id, target, source.animateur?.id ?? null);
  }

  protected moveLabel(nom: string): string {
    return $localize`:@@calendarDay.deplacer:Déplacer ${nom}:nom: vers un autre siège…`;
  }

  /**
   * The drop's twin, for the keyboard and for a single click: every other line
   * of the day, as a searchable list — its free seats to
   * move into, its people to swap with — and the answer goes through the very
   * {@link deplacer} the drop calls.
   */
  protected openMove(
    day: DayCard,
    slotSource: SlotCard,
    ligneSource: StandLine,
    poste: PosteAffectation,
    nom: string,
  ): void {
    // Not gated on `dragDropEnabled` any more: the flag governs the pointer
    // gesture, and the dialog is the way every instance moves somebody.
    if (
      this.editingLocked() ||
      this.estLigneVerrouillee(slotSource, ligneSource) ||
      this.isDayLocked(day)
    ) {
      return;
    }
    // The whole day, not only the lines the filters leave on screen: the
    // destination is typed into the dialog, it need not be visible first. But
    // only the lines a drop would accept: a locked stand or timeslot refuses
    // the drag, and must refuse its keyboard twin just the same.
    const targets = moveTargets(
      this.planning()?.postes ?? [],
      poste,
      (candidat) =>
        this.verrous.estStandVerrouille(candidat.stand?.id) ||
        this.verrous.estCreneauVerrouille(candidat.creneau?.id),
    );
    openMoveDialog(this.dialog, {
      title: $localize`:@@calendarDay.deplacer.titre:Déplacer ${nom}:nom:`,
      targets,
      targetLabel: $localize`:@@calendarDay.deplacer.cible:Vers quel siège ?`,
    })
      .afterClosed()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((choix) => {
        if (choix) {
          void this.deplacer(poste.id, choix.target, poste.animateur?.id ?? null);
        }
      });
  }

  private async deplacer(
    posteSourceId: string,
    posteCibleId: string,
    occupant: string | null,
  ): Promise<void> {
    try {
      const simulation = await this.explications.deplacer(
        posteSourceId,
        { posteId: posteCibleId },
        occupant,
      );
      this.notifications.notify({
        ...resumeDeplacement(simulation, (id) => this.nomDe(id)),
        variant: 'success',
      });
      // The persisted plan moved under the cached one: the page drops the
      // cache and re-reads — the same care the Siège panel takes after a gesture.
      this.rechargement.emit();
    } catch (error) {
      this.notifications.notify({
        title: $localize`:@@calendarDay.depotRefuse:Déplacement refusé`,
        message: errorMessage(error),
        variant: 'error',
      });
      // The refusal may be « this seat moved under you »: re-read, so the
      // second attempt is made on what is actually there.
      this.rechargement.emit();
    }
  }

  private nomDe(animateurId: string): string {
    const animateur = this.planning()?.animateurs?.find((candidat) => candidat.id === animateurId);
    return animateur
      ? `${animateur.prenom ?? ''} ${animateur.nom ?? ''}`.trim() || animateurId
      : animateurId;
  }

  /** True for a stand-line with some, but fewer than its generated seats, animateurs — fully unassigned (0) is already flagged separately. */
  protected isUnderstaffed(stand: StandLine): boolean {
    return isStandLineUnderstaffed(stand);
  }

  protected understaffedTooltip(stand: StandLine): string {
    return $localize`:@@calendarDay.understaffed:Sous-effectif : ${stand.entries.length}:count: / ${stand.effectifRequis}:min: animateur(s) affecté(s)`;
  }

  /** `09:00` of a `09:00:00`: the table never prints seconds. */
  protected heure(time: string): string {
    return formatHeure(time);
  }

  /** True for a meal-pause coverage line: reduced headcount on purpose, shown as an indication. */
  protected isCouverturePause(stand: StandLine): boolean {
    return stand.couverturePause;
  }

  protected readonly couverturePauseTooltip = $localize`:@@calendar.couverturePause:Effectif réduit pendant la pause repas — choix de couverture assumé, pas un manque d'animateurs`;

  /** True for a stand-line with at least one filled seat lacking the administrator's appreciation for it. */
  protected hasAppreciationMismatch(stand: StandLine): boolean {
    return isStandLineSansAppreciation(stand);
  }

  protected appreciationMismatchTooltip = $localize`:@@calendarDay.appreciationMismatch:Appréciation non couverte : au moins un animateur affecté n'a pas d'appréciation sur une typologie de ce stand`;

  /**
   * A seat was clicked — a name or a free seat: the page opens its Siège
   * panel, which explains it and carries every gesture on it. The panel reads
   * the plan this view was handed; nothing is fetched here.
   */
  protected openSeat(posteId: string): void {
    this.seatSelected.emit(posteId);
  }
}

/** True for a stand-line with some, but fewer than its generated seats, animateurs — fully unassigned (0) is already flagged separately. */
function isStandLineUnderstaffed(stand: StandLine): boolean {
  return stand.entries.length > 0 && stand.entries.length < stand.effectifRequis;
}

/** True for a stand-line with at least one filled seat whose animateur has no appreciation on this stand's typologies. */
function isStandLineSansAppreciation(stand: StandLine): boolean {
  return stand.entries.some(
    (entry) =>
      entry.poste.animateur &&
      entry.poste.stand &&
      !aUneAppreciationPour(entry.poste.animateur, entry.poste.stand),
  );
}

/** True when any stand-line across any créneau of `slots` has an appreciation mismatch — drives the day-card's indicator. */
function hasAppreciationMismatchIn(slots: SlotCard[]): boolean {
  return slots.some((slot) => slot.stands.some(isStandLineSansAppreciation));
}

export function buildDays(postes: PosteAffectation[]): DayCard[] {
  const days = new Map<
    number,
    { jour: number; date: string | null; creneaux: Map<number, Creneau> }
  >();
  const assignments = new Map<
    number,
    Map<
      string,
      {
        stand: Stand;
        heureDebut: string;
        heureFin: string;
        entries: AssignedEntry[];
        postesLibres: PosteAffectation[];
        sieges: number;
      }
    >
  >();

  postes.forEach((poste) => {
    const creneau = poste.creneau;
    const stand = poste.stand;
    if (!creneau || !stand) {
      return;
    }
    let day = days.get(creneau.jour);
    if (!day) {
      day = { jour: creneau.jour, date: creneau.date ?? null, creneaux: new Map() };
      days.set(creneau.jour, day);
    }
    day.creneaux.set(creneau.id, creneau);

    // A poste's own window if a partial closure (issue #60) narrowed it,
    // otherwise the créneau's full hours — two segments of the same stand
    // and créneau become two separate lines, each keyed by its own window.
    const heureDebut = poste.heureDebutEffective ?? creneau.heureDebut;
    const heureFin = poste.heureFinEffective ?? creneau.heureFin;

    let standMap = assignments.get(creneau.id);
    if (!standMap) {
      standMap = new Map();
      assignments.set(creneau.id, standMap);
    }
    const key = `${stand.id}::${heureDebut}::${heureFin}`;
    let entry = standMap.get(key);
    if (!entry) {
      entry = { stand, heureDebut, heureFin, entries: [], postesLibres: [], sieges: 0 };
      standMap.set(key, entry);
    }
    // Every poste is one seat this line has to fill, whoever ends up on it.
    entry.sieges += 1;
    if (poste.animateur) {
      const label = `${poste.animateur.prenom ?? ''} ${poste.animateur.nom ?? ''}`.trim();
      entry.entries.push({ poste, label });
    } else {
      entry.postesLibres.push(poste);
    }
  });

  return Array.from(days.values())
    .sort((left, right) => left.jour - right.jour)
    .map((day) => {
      const slots = Array.from(day.creneaux.values())
        .sort((left, right) => `${left.heureDebut}`.localeCompare(`${right.heureDebut}`))
        .map((creneau) => ({
          creneauId: creneau.id,
          heureDebut: creneau.heureDebut,
          heureFin: creneau.heureFin,
          stands: Array.from(assignments.get(creneau.id)?.values() ?? [])
            .map((entry) => ({
              standId: entry.stand.id,
              standNom: entry.stand.nom || entry.stand.id,
              emplacementNom: entry.stand.emplacement?.nom ?? null,
              heureDebut: entry.heureDebut,
              heureFin: entry.heureFin,
              entries: entry.entries,
              postesLibres: entry.postesLibres,
              effectifRequis: entry.sieges,
              couverturePause: creneau.couverturePause === true,
            }))
            .sort(
              (left, right) =>
                left.standNom.localeCompare(right.standNom) ||
                left.heureDebut.localeCompare(right.heureDebut),
            ),
        }));
      return {
        jour: day.jour,
        date: day.date,
        title: day.date
          ? $localize`:@@calendarDay.dayTitleWithDate:Jour ${day.jour}:jour: — ${day.date}:date:`
          : $localize`:@@calendarDay.dayTitle:Jour ${day.jour}:jour:`,
        slots,
        understaffed: slots.some((slot) => slot.stands.some(isStandLineUnderstaffed)),
        appreciationMismatch: hasAppreciationMismatchIn(slots),
      };
    });
}
