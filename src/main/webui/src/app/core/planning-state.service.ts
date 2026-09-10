// Shared planning state and lazy loading of a solved planning.

import { Injectable, inject, signal } from '@angular/core';
import { ApiService } from './api.service';
import { PlanningEvenement } from './models';

@Injectable({ providedIn: 'root' })
export class PlanningStateService {
  /** Planning solved (or loaded) during this session, null when none yet. */
  private readonly _lastSolvedPlanning = signal<PlanningEvenement | null>(null);
  readonly lastSolvedPlanning = this._lastSolvedPlanning.asReadonly();

  private readonly api = inject(ApiService);

  set(planning: PlanningEvenement | null): void {
    this._lastSolvedPlanning.set(planning);
  }

  /**
   * Read-only planning source for the display pages (calendars) and exports.
   * Returns the planning solved during this session if there is one, otherwise
   * the last solution persisted in the database. It never starts a solve: only
   * the Administration page may launch solver jobs, so switching tabs can no
   * longer spawn parallel solver runs.
   */
  async loadForDisplay(): Promise<PlanningEvenement> {
    const current = this.lastSolvedPlanning();
    if (current) {
      return current;
    }
    return this.api.get<PlanningEvenement>('/api/planning/persisted');
  }

  /**
   * Same read-only source, but fails loudly when nothing has been solved yet:
   * used by actions that cannot produce anything without a planning (exports).
   */
  async require(): Promise<PlanningEvenement> {
    const planning = await this.loadForDisplay();
    if (!planning || (planning.postes ?? []).length === 0) {
      throw new Error(
        $localize`:@@planningState.noneAvailable:Aucun planning disponible pour le moment. Lancez d'abord « Calculer le planning ».`,
      );
    }
    return planning;
  }
}
