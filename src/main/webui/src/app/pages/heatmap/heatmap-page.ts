import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ApiService } from '../../core/api.service';
import { PlanningStateService } from '../../core/planning-state.service';
import { PlanningFestival, PosteAffectation, TypologieItem } from '../../core/models';
import { standTypologies, typologieColorClass, typologieLabel, typologieLabels } from '../../core/typologie-colors';

export type HeatmapView = 'stand' | 'animateur';

export type HeatmapLevel = 'none' | 'ok' | 'warning' | 'critical';

export interface HeatmapDayColumn {
  jour: number;
  date: string | null;
  label: string;
}

export interface HeatmapCell {
  jour: number;
  level: HeatmapLevel;
  label: string;
  tooltip: string;
}

/** A coloured dot shown next to a row label, one per game typologie covered. */
export interface HeatmapTypologieBadge {
  id: string;
  label: string;
  colorClass: string;
}

export interface HeatmapRow {
  id: string;
  label: string;
  /** Total load across the whole period — stands sort alphabetically instead, see {@link buildStandHeatmap}. */
  total: number;
  /** Row-header tooltip, empty when there is nothing more to say than the label itself. */
  headerTooltip: string;
  /** Distinct typologies of the stands covered by this row; empty in the stand view. */
  typologies: HeatmapTypologieBadge[];
  cells: HeatmapCell[];
}

export interface HeatmapTable {
  days: HeatmapDayColumn[];
  rows: HeatmapRow[];
}

/**
 * Read-only heatmap for issue #68: at-a-glance load per day, crossed with
 * either stand (coverage gaps: filled vs. required seats) or animateur
 * (overload: how many postes land on the same day). Aggregated client-side
 * from `PlanningFestival.postes`, the same read-only data source and pattern
 * (`planningState.loadForDisplay()` + a pure builder function) as
 * `calendar-day-page.ts`'s `buildDays()` — no dedicated backend endpoint exists.
 */
@Component({
  selector: 'app-heatmap-page',
  imports: [
    MatCardModule,
    MatButtonModule,
    MatButtonToggleModule,
    MatIconModule,
    MatProgressBarModule,
    MatTooltipModule,
    MatFormFieldModule,
    MatInputModule,
    FormsModule
  ],
  templateUrl: './heatmap-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class HeatmapPage {
  protected readonly loading = signal(false);
  protected readonly error = signal('');
  protected readonly planning = signal<PlanningFestival | null>(null);
  protected readonly view = signal<HeatmapView>('stand');
  protected readonly animateurFilter = signal('');
  protected readonly standColumnLabel = $localize`:@@heatmap.column.stand:Stand`;
  protected readonly animateurColumnLabel = $localize`:@@heatmap.column.animateur:Animateur`;
  /** Typologie referential, only used to turn ids into display labels. */
  protected readonly typologies = signal<TypologieItem[]>([]);

  private readonly api = inject(ApiService);
  private readonly planningState = inject(PlanningStateService);

  private readonly postes = computed(() => this.planning()?.postes ?? []);

  protected readonly standTable = computed<HeatmapTable>(() => buildStandHeatmap(this.postes()));

  protected readonly animateurTable = computed<HeatmapTable>(() => {
    const table = buildAnimateurHeatmap(this.postes(), typologieLabels(this.typologies()));
    const query = this.animateurFilter().trim().toLocaleLowerCase();
    if (!query) {
      return table;
    }
    return { days: table.days, rows: table.rows.filter((row) => row.label.toLocaleLowerCase().includes(query)) };
  });

  protected readonly activeTable = computed<HeatmapTable>(() =>
    this.view() === 'stand' ? this.standTable() : this.animateurTable()
  );

  constructor() {
    void this.refresh();
  }

  protected async refresh(): Promise<void> {
    this.loading.set(true);
    this.error.set('');
    try {
      const [planning, typologies] = await Promise.all([
        this.planningState.loadForDisplay(),
        // Labels only: a missing referential degrades the badges to raw ids
        // rather than failing the whole heatmap.
        this.api.get<TypologieItem[]>('/api/typologies').catch(() => [])
      ]);
      this.planning.set(planning);
      this.typologies.set(typologies);
    } catch (error) {
      this.planning.set(null);
      const message = error instanceof Error ? error.message : String(error);
      this.error.set($localize`:@@common.errorPrefix:Erreur : ${message}:message:`);
    } finally {
      this.loading.set(false);
    }
  }

  protected setView(view: HeatmapView): void {
    this.view.set(view);
  }
}

/** Coverage heatmap: one row per stand, sorted alphabetically like the other calendar views. */
export function buildStandHeatmap(postes: PosteAffectation[]): HeatmapTable {
  const days = buildDayColumns(postes);
  const stands = new Map<string, string>();
  const counts = new Map<string, Map<number, { total: number; filled: number }>>();

  postes.forEach((poste) => {
    const stand = poste.stand;
    const creneau = poste.creneau;
    if (!stand || !creneau) {
      return;
    }
    stands.set(stand.id, stand.nom || stand.id);
    let byDay = counts.get(stand.id);
    if (!byDay) {
      byDay = new Map();
      counts.set(stand.id, byDay);
    }
    const cell = byDay.get(creneau.jour) ?? { total: 0, filled: 0 };
    cell.total += 1;
    if (poste.animateur) {
      cell.filled += 1;
    }
    byDay.set(creneau.jour, cell);
  });

  const rows: HeatmapRow[] = Array.from(stands.entries())
    .sort((left, right) => left[1].localeCompare(right[1]))
    .map(([standId, standNom]) => {
      const byDay = counts.get(standId);
      let total = 0;
      const cells = days.map((day) => {
        const cell = byDay?.get(day.jour);
        if (!cell || cell.total === 0) {
          return { jour: day.jour, level: 'none' as const, label: '', tooltip: standDayTooltip(standNom, day, null) };
        }
        total += cell.total - cell.filled;
        const level: HeatmapLevel = cell.filled === 0 ? 'critical' : cell.filled < cell.total ? 'warning' : 'ok';
        return {
          jour: day.jour,
          level,
          label: `${cell.filled}/${cell.total}`,
          tooltip: standDayTooltip(standNom, day, cell)
        };
      });
      return { id: standId, label: standNom, total, headerTooltip: '', typologies: [], cells };
    });

  return { days, rows };
}

/** Load heatmap: one row per animateur with at least one poste, ranked by total postes (heaviest first). */
export function buildAnimateurHeatmap(postes: PosteAffectation[], labels: Map<string, string> = new Map()): HeatmapTable {
  const days = buildDayColumns(postes);
  const animateurs = new Map<string, string>();
  const counts = new Map<string, Map<number, number>>();
  const standsByAnimateur = new Map<string, Set<string>>();
  const typologiesByAnimateur = new Map<string, Set<string>>();

  postes.forEach((poste) => {
    const animateur = poste.animateur;
    const creneau = poste.creneau;
    if (!animateur || !creneau) {
      return;
    }
    animateurs.set(animateur.id, `${animateur.prenom ?? ''} ${animateur.nom ?? ''}`.trim() || animateur.id);
    const standNom = poste.stand?.nom || poste.stand?.id;
    if (standNom) {
      let stands = standsByAnimateur.get(animateur.id);
      if (!stands) {
        stands = new Set();
        standsByAnimateur.set(animateur.id, stands);
      }
      stands.add(standNom);
    }
    let typologies = typologiesByAnimateur.get(animateur.id);
    if (!typologies) {
      typologies = new Set();
      typologiesByAnimateur.set(animateur.id, typologies);
    }
    standTypologies(poste.stand).forEach((typologie) => typologies!.add(typologie));
    let byDay = counts.get(animateur.id);
    if (!byDay) {
      byDay = new Map();
      counts.set(animateur.id, byDay);
    }
    byDay.set(creneau.jour, (byDay.get(creneau.jour) ?? 0) + 1);
  });

  const rows: HeatmapRow[] = Array.from(animateurs.entries()).map(([animateurId, label]) => {
    const byDay = counts.get(animateurId);
    let total = 0;
    const cells = days.map((day) => {
      const count = byDay?.get(day.jour) ?? 0;
      total += count;
      return {
        jour: day.jour,
        level: animateurLoadLevel(count),
        label: count > 0 ? String(count) : '',
        tooltip: animateurDayTooltip(label, day, count)
      };
    });
    const typologies = buildTypologieBadges(typologiesByAnimateur.get(animateurId), labels);
    return {
      id: animateurId,
      label,
      total,
      headerTooltip: animateurStandsTooltip(standsByAnimateur.get(animateurId), typologies),
      typologies,
      cells
    };
  });

  return { days, rows: rows.sort((left, right) => right.total - left.total || left.label.localeCompare(right.label)) };
}

/** Distinct typologies of an animateur's stands, sorted by label, with their colour. */
function buildTypologieBadges(typologies: Set<string> | undefined, labels: Map<string, string>): HeatmapTypologieBadge[] {
  return Array.from(typologies ?? [])
    .map((id) => ({ id, label: typologieLabel(labels, id), colorClass: typologieColorClass(id) }))
    .sort((left, right) => left.label.localeCompare(right.label));
}

/**
 * Row-header tooltip listing the distinct stands the animateur works on and the
 * game typologies they span — the daily cells only count postes, which says
 * nothing about how many different stands they have to cover over the festival,
 * nor how many different games they have to learn.
 */
function animateurStandsTooltip(stands: Set<string> | undefined, typologies: HeatmapTypologieBadge[]): string {
  const noms = Array.from(stands ?? []).sort((left, right) => left.localeCompare(right));
  if (noms.length === 0) {
    return $localize`:@@heatmap.animateur.standsNone:Aucun stand affecté`;
  }
  const liste = noms.join(', ');
  const standsLabel = $localize`:@@heatmap.animateur.stands:${noms.length}:count: stand(s) : ${liste}:stands:`;
  if (typologies.length === 0) {
    return standsLabel;
  }
  const listeTypologies = typologies.map((typologie) => typologie.label).join(', ');
  const typologiesLabel = $localize`:@@heatmap.animateur.typologies:${typologies.length}:count: typologie(s) de jeu : ${listeTypologies}:typologies:`;
  return `${standsLabel} — ${typologiesLabel}`;
}

function animateurLoadLevel(count: number): HeatmapLevel {
  if (count === 0) {
    return 'none';
  }
  if (count === 1) {
    return 'ok';
  }
  return count === 2 ? 'warning' : 'critical';
}

function buildDayColumns(postes: PosteAffectation[]): HeatmapDayColumn[] {
  const days = new Map<number, string | null>();
  postes.forEach((poste) => {
    const creneau = poste.creneau;
    if (!creneau) {
      return;
    }
    if (!days.has(creneau.jour) || (!days.get(creneau.jour) && creneau.date)) {
      days.set(creneau.jour, creneau.date ?? null);
    }
  });
  return Array.from(days.entries())
    .sort((left, right) => left[0] - right[0])
    .map(([jour, date]) => ({ jour, date, label: $localize`:@@heatmap.dayColumn:J${jour}:jour:` }));
}

function standDayTooltip(standNom: string, day: HeatmapDayColumn, cell: { total: number; filled: number } | null): string {
  const dayLabel = dayLabelForTooltip(day);
  if (!cell || cell.total === 0) {
    return $localize`:@@heatmap.stand.tooltipNone:${standNom}:stand: — ${dayLabel}:day: : pas de créneau`;
  }
  return $localize`:@@heatmap.stand.tooltip:${standNom}:stand: — ${dayLabel}:day: : ${cell.filled}:filled: / ${cell.total}:total: poste(s) pourvu(s)`;
}

function animateurDayTooltip(label: string, day: HeatmapDayColumn, count: number): string {
  const dayLabel = dayLabelForTooltip(day);
  if (count === 0) {
    return $localize`:@@heatmap.animateur.tooltipNone:${label}:animateur: — ${dayLabel}:day: : aucun poste`;
  }
  return $localize`:@@heatmap.animateur.tooltip:${label}:animateur: — ${dayLabel}:day: : ${count}:count: poste(s)`;
}

function dayLabelForTooltip(day: HeatmapDayColumn): string {
  return day.date
    ? $localize`:@@calendarDay.dayTitleWithDate:Jour ${day.jour}:jour: — ${day.date}:date:`
    : $localize`:@@calendarDay.dayTitle:Jour ${day.jour}:jour:`;
}
