import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ApiService } from '../../core/api.service';
import { PlanningStateService } from '../../core/planning-state.service';
import { Creneau, PersistenceStatus, PosteAffectation, Stand } from '../../core/models';

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

interface SlotCard {
  creneauId: number;
  heureDebut: string;
  heureFin: string;
  stands: StandLine[];
}

interface DayCard {
  jour: number;
  title: string;
  slots: SlotCard[];
}

/**
 * Read-only calendar grouped by festival day. Also displays how many
 * assignments are currently persisted in database.
 */
@Component({
  selector: 'app-calendar-day-page',
  imports: [MatCardModule, MatButtonModule, MatIconModule, MatProgressBarModule, MatTooltipModule],
  templateUrl: './calendar-day-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class CalendarDayPage {
  protected readonly loading = signal(false);
  protected readonly error = signal('');
  protected readonly persistedCount = signal<string>('?');
  protected readonly postes = signal<PosteAffectation[]>([]);
  protected readonly unassignedLabel = $localize`:@@calendarMonth.unassigned:(non assigné)`;

  private readonly api = inject(ApiService);
  private readonly planningState = inject(PlanningStateService);

  protected readonly days = computed<DayCard[]>(() => buildDays(this.postes()));

  constructor() {
    void this.refresh();
  }

  protected async refresh(): Promise<void> {
    this.loading.set(true);
    this.error.set('');
    await this.refreshPersistedCount();
    try {
      const planning = await this.planningState.loadForDisplay();
      this.postes.set(planning.postes ?? []);
    } catch (error) {
      this.postes.set([]);
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

  /** True for a stand-line with some, but fewer than `effectifMin`, animateurs — fully unassigned (0) is already flagged separately. */
  protected isUnderstaffed(stand: StandLine): boolean {
    return stand.names.length > 0 && stand.names.length < stand.effectifMin;
  }

  protected understaffedTooltip(stand: StandLine): string {
    return $localize`:@@calendarDay.understaffed:Sous-effectif : ${stand.names.length}:count: / ${stand.effectifMin}:min: animateur(s) affecté(s)`;
  }
}

export function buildDays(postes: PosteAffectation[]): DayCard[] {
  const days = new Map<number, { jour: number; date: string | null; creneaux: Map<number, Creneau> }>();
  const assignments = new Map<
    number,
    Map<string, { stand: Stand; heureDebut: string; heureFin: string; names: string[] }>
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
      entry = { stand, heureDebut, heureFin, names: [] };
      standMap.set(key, entry);
    }
    if (poste.animateur) {
      entry.names.push(`${poste.animateur.prenom ?? ''} ${poste.animateur.nom ?? ''}`.trim());
    }
  });

  return Array.from(days.values())
    .sort((left, right) => left.jour - right.jour)
    .map((day) => ({
      jour: day.jour,
      title: day.date
        ? $localize`:@@calendarDay.dayTitleWithDate:Jour ${day.jour}:jour: — ${day.date}:date:`
        : $localize`:@@calendarDay.dayTitle:Jour ${day.jour}:jour:`,
      slots: Array.from(day.creneaux.values())
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
              names: entry.names,
              effectifMin: Math.max(1, entry.stand.effectifMin)
            }))
            .sort(
              (left, right) => left.standNom.localeCompare(right.standNom) || left.heureDebut.localeCompare(right.heureDebut)
            )
        }))
    }));
}
