import { DecimalPipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatSortModule, Sort } from '@angular/material/sort';
import { MatTableModule } from '@angular/material/table';
import { ApiService } from '../../core/api.service';
import { HeuresAnimateur, HeuresRapport } from '../../core/models';
import { PlanningStateService } from '../../core/planning-state.service';
import { OutputPanel } from '../../shared/output-panel';

/**
 * Hours screen: hours planned per animateur, broken down by ISO calendar
 * week plus a total, computed server-side from the current planning
 * (`/api/planning/hours`) so the midnight-crossing duration edge case is
 * handled in exactly one place (Creneau.getDureeMinutes on the backend).
 */
@Component({
  selector: 'app-hours-page',
  imports: [MatCardModule, MatButtonModule, MatIconModule, MatTableModule, MatSortModule, DecimalPipe, OutputPanel],
  templateUrl: './hours-page.html'
})
export class HoursPage {
  protected readonly output = signal('');
  protected readonly busy = signal(false);
  protected readonly exportBusy = signal(false);
  protected readonly rapport = signal<HeuresRapport | null>(null);
  protected readonly columns = computed(() => ['animateur', ...(this.rapport()?.semaines ?? []), 'total']);
  protected readonly sort = signal<Sort>({ active: '', direction: '' });
  protected readonly sortedAnimateurs = computed(() => {
    const animateurs = this.rapport()?.animateurs ?? [];
    const { active, direction } = this.sort();
    if (!active || !direction) {
      return animateurs;
    }
    const factor = direction === 'asc' ? 1 : -1;
    return [...animateurs].sort((a, b) => factor * compareByColumn(a, b, active));
  });

  private readonly api = inject(ApiService);
  private readonly planningState = inject(PlanningStateService);

  constructor() {
    void this.load();
  }

  protected async load(): Promise<void> {
    this.busy.set(true);
    this.output.set('');
    try {
      const planning = await this.planningState.require();
      this.rapport.set(await this.api.post<HeuresRapport>('/api/planning/hours', planning));
    } catch (error) {
      this.rapport.set(null);
      this.output.set(`Erreur : ${message(error)}`);
    } finally {
      this.busy.set(false);
    }
  }

  protected hoursFor(animateurId: string, semaine: string): number {
    const ligne = this.rapport()?.animateurs.find((a) => a.animateurId === animateurId);
    return ligne?.heuresParSemaine[semaine] ?? 0;
  }

  protected async onExportCsv(): Promise<void> {
    this.exportBusy.set(true);
    this.output.set("Construction de l'export CSV...");
    try {
      const planning = await this.planningState.require();
      this.output.set(
        await this.api.downloadPost('/api/planning/hours/export', 'heures-planning.csv', planning, 'text/csv')
      );
    } catch (error) {
      this.output.set(`Erreur : ${message(error)}`);
    } finally {
      this.exportBusy.set(false);
    }
  }
}

function message(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

function compareByColumn(a: HeuresAnimateur, b: HeuresAnimateur, column: string): number {
  if (column === 'animateur') {
    return a.nom.localeCompare(b.nom);
  }
  const valueA = column === 'total' ? a.total : (a.heuresParSemaine[column] ?? 0);
  const valueB = column === 'total' ? b.total : (b.heuresParSemaine[column] ?? 0);
  return valueA - valueB;
}
