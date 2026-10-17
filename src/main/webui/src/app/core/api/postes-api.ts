// The calls on one seat of the persisted plan — `/api/postes/*`: « Pourquoi
// lui ? » read server-side, « Placer », the move of the day views, and the
// replacement and release of the Siège panel and the Jour J page. See
// planning-api.ts for why the paths live here and not in the pages.

import { Injectable, inject } from '@angular/core';
import { ApiService } from '../api.service';
import { AffectationExplanation, DeplacementSimulation } from '../models';
import { dropEmpty } from './drop-empty';

@Injectable({ providedIn: 'root' })
export class PostesApi {
  private readonly api = inject(ApiService);

  /**
   * « Pourquoi lui ? » on the persisted plan, prepared server-side under the
   * edition's rules — a constraint switched off reproaches nobody. No body:
   * the screen's copy of the plan carries no rules.
   */
  explanation(posteId: string): Promise<AffectationExplanation> {
    return this.api.get<AffectationExplanation>(
      `/api/postes/${encodeURIComponent(posteId)}/explication`,
    );
  }

  /**
   * « Placer » : seats somebody on a seat nobody holds. Refused when the seat
   * is no longer free (409), when a hard rule would break or a lock covers the
   * seat or the person (400, the rule named). Answers the scores before and
   * after, so the caller can warn about what the medium level lost.
   */
  place(posteId: string, animateurId: string): Promise<DeplacementSimulation> {
    return this.api.post<DeplacementSimulation>(
      `/api/postes/${encodeURIComponent(posteId)}/placement?animateur=${encodeURIComponent(animateurId)}`,
      null,
    );
  }

  /**
   * A seat dropped somewhere else (issue #308): on another seat, or on a
   * person. Simulated and refused server-side when it would break a hard
   * rule — one round trip, since the refusal names the rule; the day views
   * do not simulate first. Writes on success, and answers what it did.
   */
  move(
    posteId: string,
    target: { posteId?: string; animateurId?: string },
    /** Who the view believes holds the seat: the server refuses (409) if somebody else does now. */
    occupant?: string | null,
  ): Promise<DeplacementSimulation> {
    const params = new URLSearchParams({
      cible: target.posteId ?? '',
      animateur: target.animateurId ?? '',
      occupant: occupant ?? '',
    });
    dropEmpty(params);
    return this.api.post<DeplacementSimulation>(
      `/api/postes/${encodeURIComponent(posteId)}/deplacement?${params}`,
      null,
    );
  }

  /**
   * Hands one seat to `animateurId` in the persisted plan, and nothing else
   * changes — or, `animateurId` null, empties it (« Libérer »). `occupant` is
   * who the screen shows on the seat: the server refuses (409) and writes
   * nothing if somebody else holds it by then.
   */
  applyRepair(
    posteId: string,
    animateurId: string | null,
    occupant?: string | null,
  ): Promise<void> {
    const params = new URLSearchParams({
      animateurId: animateurId ?? '',
      occupant: occupant ?? '',
    });
    dropEmpty(params);
    return this.api.post<void>(
      `/api/postes/${encodeURIComponent(posteId)}/affectation?${params}`,
      null,
    );
  }
}
