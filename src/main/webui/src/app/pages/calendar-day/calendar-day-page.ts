import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatCardModule } from '@angular/material/card';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ApiService } from '../../core/api.service';
import { PlanningStateService } from '../../core/planning-state.service';
import { VerrouillageStore } from '../../core/verrouillage.store';
import { Animateur, Creneau, PersistenceStatus, PlanningFestival, PosteAffectation, Stand } from '../../core/models';
import { AffectationExplanationDialog } from '../../shared/affectation-explanation-dialog';

interface AssignedEntry {
  poste: PosteAffectation;
  label: string;
}

interface StandLine {
  /** Identity of the line: two distinct stands may well share the same name. */
  standId: string;
  standNom: string;
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
 * Read-only calendar grouped by festival day. Also displays how many
 * assignments are currently persisted in database.
 */
@Component({
  selector: 'app-calendar-day-page',
  imports: [
    FormsModule,
    MatCardModule,
    MatButtonModule,
    MatCheckboxModule,
    MatIconModule,
    MatProgressBarModule,
    MatTooltipModule
  ],
  templateUrl: './calendar-day-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class CalendarDayPage {
  protected readonly loading = signal(false);
  protected readonly error = signal('');
  protected readonly persistedCount = signal<string>('?');
  protected readonly planning = signal<PlanningFestival | null>(null);
  protected readonly unassignedLabel = $localize`:@@calendarMonth.unassigned:(non assigné)`;
  protected readonly pourquoiLuiLabel = $localize`:@@affectationExplanation.tooltip:Pourquoi lui ?`;

  protected readonly verrous = inject(VerrouillageStore);

  private readonly api = inject(ApiService);
  private readonly planningState = inject(PlanningStateService);
  private readonly dialog = inject(MatDialog);

  /**
   * Narrows the day cards to the stand lines that need attention. A festival
   * day holds dozens of lines of which two are wrong; scrolling all of them to
   * find those two is the actual daily task this screen exists for.
   */
  protected readonly seulementProblemes = signal(false);

  private readonly toutesLesJournees = computed<DayCard[]>(() => buildDays(this.planning()?.postes ?? []));

  protected readonly days = computed<DayCard[]>(() => {
    if (!this.seulementProblemes()) {
      return this.toutesLesJournees();
    }
    return this.toutesLesJournees()
      .map((day) => ({
        ...day,
        slots: day.slots
          .map((slot) => ({
            ...slot,
            stands: slot.stands.filter(
              (stand) =>
                this.isUnderstaffed(stand) ||
                this.hasAppreciationMismatch(stand) ||
                stand.entries.length === 0
            )
          }))
          .filter((slot) => slot.stands.length > 0)
      }))
      .filter((day) => day.slots.length > 0);
  });

  constructor() {
    void this.refresh();
    // Fire-and-forget: the padlocks are an indicator, never a reason to fail
    // the calendar the user came to read.
    void this.verrous.reload().catch(() => undefined);
  }

  /** True when the whole day is frozen by a JOUR lock on the active groupe de créneaux. */
  protected estJourVerrouille(day: DayCard): boolean {
    return this.verrous.estJourVerrouille(day.date);
  }

  /** True when this stand-line is frozen, either by its stand or by its créneau. */
  protected estLigneVerrouillee(slot: SlotCard, stand: StandLine): boolean {
    return this.verrous.estStandVerrouille(stand.standId) || this.verrous.estCreneauVerrouille(slot.creneauId);
  }

  protected readonly verrouilleTooltip = $localize`:@@verrouillages.indicator:Verrouillé : ces affectations ne bougeront plus à la prochaine résolution`;

  protected async refresh(): Promise<void> {
    this.loading.set(true);
    this.error.set('');
    await this.refreshPersistedCount();
    try {
      this.planning.set(await this.planningState.loadForDisplay());
    } catch (error) {
      this.planning.set(null);
      const message = error instanceof Error ? error.message : String(error);
      this.error.set($localize`:@@common.errorPrefix:Erreur : ${message}:message:`);
    } finally {
      this.loading.set(false);
    }
  }

  private async refreshPersistedCount(): Promise<void> {
    try {
      const status = await this.api.get<PersistenceStatus>('/api/planning/persisted/count');
      this.persistedCount.set(String(status.assignments));
    } catch {
      this.persistedCount.set($localize`:@@job.scoreUnavailable:n/d`);
    }
  }

  /** True for a stand-line with some, but fewer than its generated seats, animateurs — fully unassigned (0) is already flagged separately. */
  protected isUnderstaffed(stand: StandLine): boolean {
    return isStandLineUnderstaffed(stand);
  }

  protected understaffedTooltip(stand: StandLine): string {
    return $localize`:@@calendarDay.understaffed:Sous-effectif : ${stand.entries.length}:count: / ${stand.effectifRequis}:min: animateur(s) affecté(s)`;
  }

  /** True for a meal-pause coverage line: reduced headcount on purpose, shown as an indication. */
  protected isCouverturePause(stand: StandLine): boolean {
    return stand.couverturePause;
  }

  protected readonly couverturePauseTooltip = $localize`:@@calendar.couverturePause:Effectif réduit pendant la pause repas — choix de couverture assumé, pas un manque d'animateurs`;

  protected readonly dayUnderstaffedTooltip = $localize`:@@calendarMonth.cellUnderstaffed:Au moins un stand en sous-effectif ce jour-là`;

  /** True for a stand-line with at least one filled seat lacking the administrator's appreciation for it. */
  protected hasAppreciationMismatch(stand: StandLine): boolean {
    return isStandLineSansAppreciation(stand);
  }

  protected appreciationMismatchTooltip = $localize`:@@calendarDay.appreciationMismatch:Appréciation non couverte : au moins un animateur affecté n'a pas d'appréciation sur une typologie de ce stand`;

  protected readonly dayAppreciationMismatchTooltip = $localize`:@@calendarMonth.cellAppreciationMismatch:Au moins un stand avec un écart d'appréciation ce jour-là`;

  /** Opens the "Pourquoi lui ?" dialog for one filled seat, offering every other competent animateur as a swap candidate. */
  protected openExplanation(poste: PosteAffectation): void {
    const planning = this.planning();
    if (!planning) {
      return;
    }
    const candidats = (planning.animateurs ?? []).filter(
      (animateur) =>
        animateur.id !== poste.animateur?.id && poste.stand && aUneAppreciationPour(animateur, poste.stand)
    );
    this.dialog.open(AffectationExplanationDialog, {
      data: { poste, planning, candidats },
      width: '32rem'
    });
  }
}

function aUneAppreciationPour(animateur: Animateur, stand: Stand): boolean {
  return stand.typologiesProposees.some((typologie) => typologie in (animateur.competences ?? {}));
}

/** True for a stand-line with some, but fewer than its generated seats, animateurs — fully unassigned (0) is already flagged separately. */
function isStandLineUnderstaffed(stand: StandLine): boolean {
  return stand.entries.length > 0 && stand.entries.length < stand.effectifRequis;
}

/** True for a stand-line with at least one filled seat whose animateur has no appreciation on this stand's typologies. */
function isStandLineSansAppreciation(stand: StandLine): boolean {
  return stand.entries.some(
    (entry) => entry.poste.animateur && entry.poste.stand && !aUneAppreciationPour(entry.poste.animateur, entry.poste.stand)
  );
}

/** True when any stand-line across any créneau of `slots` has an appreciation mismatch — drives the day-card's indicator. */
function hasAppreciationMismatchIn(slots: SlotCard[]): boolean {
  return slots.some((slot) => slot.stands.some(isStandLineSansAppreciation));
}

export function buildDays(postes: PosteAffectation[]): DayCard[] {
  const days = new Map<number, { jour: number; date: string | null; creneaux: Map<number, Creneau> }>();
  const assignments = new Map<
    number,
    Map<string, { stand: Stand; heureDebut: string; heureFin: string; entries: AssignedEntry[]; sieges: number }>
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
      entry = { stand, heureDebut, heureFin, entries: [], sieges: 0 };
      standMap.set(key, entry);
    }
    // Every poste is one seat this line has to fill, whoever ends up on it.
    entry.sieges += 1;
    if (poste.animateur) {
      const label = `${poste.animateur.prenom ?? ''} ${poste.animateur.nom ?? ''}`.trim();
      entry.entries.push({ poste, label });
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
              heureDebut: entry.heureDebut,
              heureFin: entry.heureFin,
              entries: entry.entries,
              effectifRequis: entry.sieges,
              couverturePause: creneau.couverturePause === true
            }))
            .sort(
              (left, right) => left.standNom.localeCompare(right.standNom) || left.heureDebut.localeCompare(right.heureDebut)
            )
        }));
      return {
        jour: day.jour,
        date: day.date,
        title: day.date
          ? $localize`:@@calendarDay.dayTitleWithDate:Jour ${day.jour}:jour: — ${day.date}:date:`
          : $localize`:@@calendarDay.dayTitle:Jour ${day.jour}:jour:`,
        slots,
        understaffed: slots.some((slot) => slot.stands.some(isStandLineUnderstaffed)),
        appreciationMismatch: hasAppreciationMismatchIn(slots)
      };
    });
}
