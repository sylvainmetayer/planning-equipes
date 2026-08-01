import { Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatDividerModule } from '@angular/material/divider';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatListModule } from '@angular/material/list';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { intlLocale } from '../../core/locale';
import { PlanningStateService } from '../../core/planning-state.service';
import { PosteAffectation } from '../../core/models';
import {
  buildMonthCells,
  getMonthStart,
  parseDateKey,
  pickDefaultDateKey,
  shiftMonth,
  toDateKey,
  uniqueById
} from '../../core/date-utils';

interface StandLine {
  standNom: string;
  names: string[];
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
    MatDividerModule
  ],
  templateUrl: './calendar-month-page.html'
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

  protected readonly dayDetailsFallback = $localize`:@@calendarMonth.dayDetails:Détails du jour`;
  protected readonly unassignedLabel = $localize`:@@calendarMonth.unassigned:(non assigné)`;

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
    const month = this.month();
    const monthKey = `${month.getFullYear()}-${String(month.getMonth() + 1).padStart(2, '0')}`;
    return pickDefaultDateKey(keys, monthKey);
  });

  protected readonly cells = computed<MonthCell[]>(() => {
    const assignments = this.assignmentsByDate();
    const month = this.month();
    const todayKey = toDateKey(new Date());
    return buildMonthCells(month).map((cellDate) => {
      const dateKey = toDateKey(cellDate);
      return {
        dateKey,
        dayNumber: cellDate.getDate(),
        otherMonth: cellDate.getMonth() !== month.getMonth(),
        today: dateKey === todayKey,
        count: assignments.get(dateKey)?.length ?? 0
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

  /** Monday-start abbreviations for the calendar header, in the current UI locale. */
  protected weekdayAbbreviations(): string[] {
    const formatter = new Intl.DateTimeFormat(intlLocale(), { weekday: 'short' });
    // 2024-01-01 was a Monday: a stable reference week, independent of the displayed month.
    return Array.from({ length: 7 }, (_, index) => formatter.format(new Date(2024, 0, 1 + index)));
  }

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
    void this.refresh();
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
}

function buildAssignmentsByDate(postes: PosteAffectation[]): Map<string, SlotEntry[]> {
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
    let line = slot.standMap.get(stand.id);
    if (!line) {
      line = { standNom: stand.nom || stand.id, names: [] };
      slot.standMap.set(stand.id, line);
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
        stands: Array.from(slot.standMap.values()).sort((left, right) =>
          left.standNom.localeCompare(right.standNom)
        )
      }))
      .sort((left, right) => `${left.heureDebut}`.localeCompare(`${right.heureDebut}`));
    result.set(dateKey, entries);
  });
  return result;
}
