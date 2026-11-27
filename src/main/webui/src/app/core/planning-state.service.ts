// Shared planning state and lazy loading of a solved planning.

import { Injectable, inject, signal } from '@angular/core';
import { ApiService } from './api.service';
import { Animateur, CompactPlanning, Creneau, PlanningEvenement, Stand } from './models';

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
  loadForDisplay(): Promise<PlanningEvenement> {
    const current = this.lastSolvedPlanning();
    if (current) {
      return Promise.resolve(current);
    }
    return this.api.get<CompactPlanning>('/api/planning/persisted').then(expandCompactPlanning);
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

/**
 * The full planning out of its compact form: each seat gets its stand,
 * timeslot and animateur back — shared instances, one per id, where the full
 * route sent a copy per seat.
 */
export function expandCompactPlanning(compact: CompactPlanning): PlanningEvenement {
  const { stands, creneaux, postes, ...rest } = compact;
  const standsById = new Map<string, Stand>(stands.map((s) => [s.id, s]));
  const creneauxById = new Map<number, Creneau>(creneaux.map((c) => [c.id, c]));
  const animateursById = new Map<string, Animateur>(rest.animateurs.map((a) => [a.id, a]));
  return {
    ...rest,
    postes: postes.map(({ standId, creneauId, animateurId, ...seat }) => ({
      ...seat,
      stand: standId === null ? null : (standsById.get(standId) ?? null),
      creneau: creneauId === null ? null : (creneauxById.get(creneauId) ?? null),
      animateur: animateurId === null ? null : (animateursById.get(animateurId) ?? null),
    })),
  };
}
