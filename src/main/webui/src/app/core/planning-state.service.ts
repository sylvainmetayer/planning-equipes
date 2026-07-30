// Shared planning state and lazy loading of a solved planning.

import { Injectable, inject, signal } from '@angular/core';
import { ApiService } from './api.service';
import { PlanningFestival } from './models';

@Injectable({ providedIn: 'root' })
export class PlanningStateService {
  /** Planning solved (or loaded) during this session, null when none yet. */
  readonly lastSolvedPlanning = signal<PlanningFestival | null>(null);

  private readonly api = inject(ApiService);

  set(planning: PlanningFestival | null): void {
    this.lastSolvedPlanning.set(planning);
  }

  /**
   * Read-only planning source for the display pages (calendars) and exports.
   * Returns the planning solved during this session if there is one, otherwise
   * the last solution persisted in the database. It never starts a solve: only
   * the Administration page may launch solver jobs, so switching tabs can no
   * longer spawn parallel solver runs.
   */
  async loadForDisplay(): Promise<PlanningFestival> {
    const current = this.lastSolvedPlanning();
    if (current) {
      return current;
    }
    return this.api.get<PlanningFestival>('/api/planning/persisted');
  }

  /**
   * Same read-only source, but fails loudly when nothing has been solved yet:
   * used by actions that cannot produce anything without a planning (exports).
   */
  async require(): Promise<PlanningFestival> {
    const planning = await this.loadForDisplay();
    if (!planning || (planning.postes ?? []).length === 0) {
      throw new Error('No planning available yet. Run "Solve with Timefold" first.');
    }
    return planning;
  }
}
