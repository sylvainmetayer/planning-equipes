// The seat writes of the day views and the repair assistant: moving a seat
// and handing it to someone, through the /api/postes/* endpoints.

import { Injectable, inject } from '@angular/core';
import { ApiService } from './api.service';
import { DeplacementSimulation } from './models';

@Injectable({ providedIn: 'root' })
export class AffectationExplanationService {
  private readonly api = inject(ApiService);

  /**
   * A seat dropped somewhere else (issue #308): on another seat, or on a
   * person. Simulated and refused server-side when it would break a hard
   * rule — one round trip, since the refusal names the rule; the day views
   * do not simulate first. Writes on success, and answers what it did.
   */
  deplacer(
    posteId: string,
    target: { posteId?: string; animateurId?: string },
    /** Who the view believes holds the seat: the server refuses (409) if somebody else does now. */
    occupant?: string | null,
  ): Promise<DeplacementSimulation> {
    const params = new URLSearchParams();
    if (target.posteId) {
      params.set('cible', target.posteId);
    }
    if (target.animateurId) {
      params.set('animateur', target.animateurId);
    }
    if (occupant) {
      params.set('occupant', occupant);
    }
    return this.api.post<DeplacementSimulation>(
      `/api/postes/${encodeURIComponent(posteId)}/deplacement?${params}`,
      null,
    );
  }

  /**
   * Applies one suggestion to the persisted plan: that seat changes hands and
   * nothing else does — or, `animateurId` null, the seat is emptied
   * (« Libérer »). `occupant` is who the screen shows on the seat: the server
   * refuses (409) and writes nothing if somebody else holds it by then.
   */
  applyRepair(
    posteId: string,
    animateurId: string | null,
    occupant?: string | null,
  ): Promise<void> {
    const params = new URLSearchParams();
    if (animateurId) {
      params.set('animateurId', animateurId);
    }
    if (occupant) {
      params.set('occupant', occupant);
    }
    const query = params.toString();
    const suffix = query ? `?${query}` : '';
    return this.api.post<void>(
      `/api/postes/${encodeURIComponent(posteId)}/affectation${suffix}`,
      null,
    );
  }
}
