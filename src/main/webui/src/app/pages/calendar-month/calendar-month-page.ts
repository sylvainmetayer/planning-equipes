import { ChangeDetectionStrategy, Component, computed, effect, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatDividerModule } from '@angular/material/divider';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatListModule } from '@angular/material/list';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ActivatedRoute, Router } from '@angular/router';
import { intlLocale } from '../../core/locale';
import { PlanningStateService } from '../../core/planning-state.service';
import { PosteAffectation } from '../../core/models';
import {
  buildMonthCells,
  getMonthStart,
  parseDateKey,
  parseMonthKey,
  pickDefaultDateKey,
  shiftMonth,
  toDateKey,
  toMonthKey,
  uniqueById
} from '../../core/date-utils';

interface StandLine {
  /** Identity of the line: two distinct stands may well share the same name. */
  standId: string;
  standNom: string;
  /**
   * The window actually staffed by `names` — the poste's effective window if
   * a partial stand closure (issue #60) narrowed it, otherwise the créneau's
   * own hours. Two segments of the same stand and créneau (one on each side
   * of a mid-créneau closure) become two separate lines, each with its own
   * window.
   */
  heureDebut: string;
  heureFin: string;
  names: string[];
  /** The stand's required headcount for this line, to flag understaffing (some but not enough names). */
  effectifMin: number;
}

interface SlotEntry {
  creneauId: number;
  heureDebut: string;
  heureFin: string;
  jour: number;
  stands: StandLine[];
}

interface MonthCell {
  dateKey: string;
  dayNumber: number;
  otherMonth: boolean;
  today: boolean;
  count: number;
  /** True when any stand-line on this date has some, but fewer than effectifMin, animateurs. */
  understaffed: boolean;
}

interface FilterOption {
  value: string;
  label: string;
}

const ALL = 'ALL';

/**
 * Read-only monthly calendar of the assignments, with animator/stand filters
 * and a day-details aside. Never triggers a solve: it reads the planning
 * solved during the session or the last one persisted in database.
 */
@Component({
  selector: 'app-calendar-month-page',
  imports: [
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatFormFieldModule,
    MatSelectModule,
    MatProgressBarModule,
    MatListModule,
    MatDividerModule,
    MatTooltipModule
  ],
  templateUrl: './calendar-month-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class CalendarMonthPage {
  protected readonly error = signal('');
  protected readonly loading = signal(false);
  protected readonly postes = signal<PosteAffectation[]>([]);
  protected readonly loaded = signal(false);

  protected readonly month = signal(getMonthStart(new Date()));
  protected readonly selectedDateKey = signal<string | null>(null);
  protected readonly animateurFilter = signal(ALL);
  protected readonly standFilter = signal(ALL);

  private readonly planningState = inject(PlanningStateService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  protected readonly dayDetailsFallback = $localize`:@@calendarMonth.dayDetails:Détails du jour`;
  protected readonly unassignedLabel = $localize`:@@calendarMonth.unassigned:(non assigné)`;
  protected readonly cellUnderstaffedTooltip = $localize`:@@calendarMonth.cellUnderstaffed:Au moins un stand en sous-effectif ce jour-là`;

  protected readonly monthLabel = computed(() =>
    this.month().toLocaleDateString(intlLocale(), { month: 'long', year: 'numeric' })
  );

  protected readonly animateurOptions = computed<FilterOption[]>(() =>
    uniqueById(this.postes().map((poste) => poste.animateur).filter((animateur) => !!animateur))
      .map((animateur) => ({
        value: animateur.id,
        label: `${animateur.prenom ?? ''} ${animateur.nom ?? ''}`.trim()
      }))
      .sort((left, right) => left.label.localeCompare(right.label))
  );

  protected readonly standOptions = computed<FilterOption[]>(() =>
    uniqueById(this.postes().map((poste) => poste.stand).filter((stand) => !!stand))
      .map((stand) => ({ value: stand.id, label: stand.nom || stand.id }))
      .sort((left, right) => left.label.localeCompare(right.label))
  );

  /** Assignments of the filtered postes, grouped by date then by timeslot. */
  private readonly assignmentsByDate = computed(() => {
    const animateurId = this.animateurFilter();
    const standId = this.standFilter();
    const filtered = this.postes().filter((poste) => {
      if (standId !== ALL && poste.stand?.id !== standId) {
        return false;
      }
      return animateurId === ALL || poste.animateur?.id === animateurId;
    });
    return buildAssignmentsByDate(filtered);
  });

  protected readonly hasData = computed(() => this.assignmentsByDate().size > 0);

  /** Selected day, falling back to the first day of the displayed month. */
  protected readonly effectiveDateKey = computed(() => {
    const assignments = this.assignmentsByDate();
    const selected = this.selectedDateKey();
    if (selected && assignments.has(selected)) {
      return selected;
    }
    const keys = Array.from(assignments.keys()).sort();
    if (keys.length === 0) {
      return null;
    }
    return pickDefaultDateKey(keys, toMonthKey(this.month()));
  });

  protected readonly cells = computed<MonthCell[]>(() => {
    const assignments = this.assignmentsByDate();
    const month = this.month();
    const todayKey = toDateKey(new Date());
    return buildMonthCells(month).map((cellDate) => {
      const dateKey = toDateKey(cellDate);
      const slots = assignments.get(dateKey);
      return {
        dateKey,
        dayNumber: cellDate.getDate(),
        otherMonth: cellDate.getMonth() !== month.getMonth(),
        today: dateKey === todayKey,
        count: slots?.length ?? 0,
        understaffed: hasUnderstaffedStand(slots)
      };
    });
  });

  protected readonly dayTitle = computed(() => {
    const dateKey = this.effectiveDateKey();
    if (!dateKey) {
      return '';
    }
    return parseDateKey(dateKey).toLocaleDateString(intlLocale(), {
      weekday: 'long',
      day: 'numeric',
      month: 'long',
      year: 'numeric'
    });
  });

  /**
   * Monday-start abbreviations for the calendar header, in the current UI
   * locale. Built once: the locale is fixed for the session (switching it
   * reloads the page), and a template function would rebuild the array — and
   * seven `Intl` formats — on every change detection pass.
   */
  protected readonly weekdayAbbreviations = buildWeekdayAbbreviations();

  protected creneauCountLabel(count: number): string {
    return count === 1
      ? $localize`:@@calendarMonth.creneauCountOne:${count}:count: créneau`
      : $localize`:@@calendarMonth.creneauCountMany:${count}:count: créneaux`;
  }

  protected readonly daySlots = computed<SlotEntry[]>(() => {
    const dateKey = this.effectiveDateKey();
    return dateKey ? (this.assignmentsByDate().get(dateKey) ?? []) : [];
  });

  constructor() {
    this.seedStateFromQueryParams();
    void this.refresh();
    // Keeps month/day/filters in the URL so a refresh (F5) restores the view
    // instead of resetting it — replaceUrl avoids piling up a history entry
    // per click while browsing (month nav, day/filter changes all go through
    // the same effect).
    effect(() => this.syncQueryParams());
  }

  private seedStateFromQueryParams(): void {
    const params = this.route.snapshot.queryParamMap;
    const month = params.get('month');
    const parsedMonth = month ? parseMonthKey(month) : null;
    if (parsedMonth) {
      this.month.set(parsedMonth);
    }
    const date = params.get('date');
    if (date) {
      this.selectedDateKey.set(date);
    }
    const animateur = params.get('animateur');
    if (animateur) {
      this.animateurFilter.set(animateur);
    }
    const stand = params.get('stand');
    if (stand) {
      this.standFilter.set(stand);
    }
  }

  private syncQueryParams(): void {
    const queryParams = {
      month: toMonthKey(this.month()),
      date: this.selectedDateKey(),
      animateur: this.animateurFilter() === ALL ? null : this.animateurFilter(),
      stand: this.standFilter() === ALL ? null : this.standFilter()
    };
    void this.router.navigate([], { relativeTo: this.route, queryParams, replaceUrl: true });
  }

  protected async refresh(): Promise<void> {
    this.loading.set(true);
    this.error.set('');
    try {
      const planning = await this.planningState.loadForDisplay();
      this.postes.set(planning.postes ?? []);
      this.loaded.set(true);
    } catch (error) {
      this.postes.set([]);
      const message = error instanceof Error ? error.message : String(error);
      this.error.set($localize`:@@common.errorPrefix:Erreur : ${message}:message:`);
    } finally {
      this.loading.set(false);
    }
  }

  protected previousMonth(): void {
    this.month.update((month) => shiftMonth(month, -1));
  }

  protected nextMonth(): void {
    this.month.update((month) => shiftMonth(month, 1));
  }

  protected today(): void {
    this.month.set(getMonthStart(new Date()));
    this.selectedDateKey.set(toDateKey(new Date()));
  }

  protected resetFilters(): void {
    this.animateurFilter.set(ALL);
    this.standFilter.set(ALL);
    this.selectedDateKey.set(null);
  }

  protected selectAnimateur(value: string): void {
    this.animateurFilter.set(value);
    this.selectedDateKey.set(null);
  }

  protected selectStand(value: string): void {
    this.standFilter.set(value);
    this.selectedDateKey.set(null);
  }

  protected selectDay(cell: MonthCell): void {
    this.selectedDateKey.set(cell.dateKey);
    if (cell.otherMonth) {
      this.month.set(getMonthStart(parseDateKey(cell.dateKey)));
    }
  }

  /** True for a stand-line with some, but fewer than `effectifMin`, animateurs — fully unassigned (0) is already flagged separately. */
  protected isUnderstaffed(stand: StandLine): boolean {
    return isStandLineUnderstaffed(stand);
  }

  protected understaffedTooltip(stand: StandLine): string {
    return $localize`:@@calendarDay.understaffed:Sous-effectif : ${stand.names.length}:count: / ${stand.effectifMin}:min: animateur(s) affecté(s)`;
  }
}

/** True for a stand-line with some, but fewer than `effectifMin`, animateurs — fully unassigned (0) is already flagged separately. */
function isStandLineUnderstaffed(stand: StandLine): boolean {
  return stand.names.length > 0 && stand.names.length < stand.effectifMin;
}

/** True when any stand-line across any créneau of `slots` is understaffed — drives the month grid's day-cell indicator. */
export function hasUnderstaffedStand(slots: SlotEntry[] | undefined): boolean {
  return (slots ?? []).some((slot) => slot.stands.some(isStandLineUnderstaffed));
}

function buildWeekdayAbbreviations(): string[] {
  const formatter = new Intl.DateTimeFormat(intlLocale(), { weekday: 'short' });
  // 2024-01-01 was a Monday: a stable reference week, independent of the displayed month.
  return Array.from({ length: 7 }, (_, index) => formatter.format(new Date(2024, 0, 1 + index)));
}

export function buildAssignmentsByDate(postes: PosteAffectation[]): Map<string, SlotEntry[]> {
  const byDate = new Map<string, Map<number, SlotEntry & { standMap: Map<string, StandLine> }>>();

  postes.forEach((poste) => {
    const creneau = poste.creneau;
    const stand = poste.stand;
    if (!creneau || !stand || !creneau.date) {
      return;
    }
    let slots = byDate.get(creneau.date);
    if (!slots) {
      slots = new Map();
      byDate.set(creneau.date, slots);
    }
    let slot = slots.get(creneau.id);
    if (!slot) {
      slot = {
        creneauId: creneau.id,
        heureDebut: creneau.heureDebut,
        heureFin: creneau.heureFin,
        jour: creneau.jour,
        stands: [],
        standMap: new Map()
      };
      slots.set(creneau.id, slot);
    }
    const heureDebut = poste.heureDebutEffective ?? creneau.heureDebut;
    const heureFin = poste.heureFinEffective ?? creneau.heureFin;
    const lineKey = `${stand.id}::${heureDebut}::${heureFin}`;
    let line = slot.standMap.get(lineKey);
    if (!line) {
      line = {
        standId: stand.id,
        standNom: stand.nom || stand.id,
        heureDebut,
        heureFin,
        names: [],
        effectifMin: Math.max(1, stand.effectifMin)
      };
      slot.standMap.set(lineKey, line);
    }
    if (poste.animateur) {
      line.names.push(`${poste.animateur.prenom ?? ''} ${poste.animateur.nom ?? ''}`.trim());
    }
  });

  const result = new Map<string, SlotEntry[]>();
  byDate.forEach((slots, dateKey) => {
    const entries = Array.from(slots.values())
      .map((slot) => ({
        creneauId: slot.creneauId,
        heureDebut: slot.heureDebut,
        heureFin: slot.heureFin,
        jour: slot.jour,
        stands: Array.from(slot.standMap.values()).sort(
          (left, right) => left.standNom.localeCompare(right.standNom) || left.heureDebut.localeCompare(right.heureDebut)
        )
      }))
      .sort((left, right) => `${left.heureDebut}`.localeCompare(`${right.heureDebut}`));
    result.set(dateKey, entries);
  });
  return result;
}
