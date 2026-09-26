// The Siège panel's own calls on one seat of the persisted plan — `/api/postes/*`:
// « Pourquoi lui ? » read server-side, and « Placer ». The release, the
// replacement and the move go through `affectation-explanation.service.ts`;
// see planning-api.ts for why the paths live here and not in the pages.

import { Injectable, inject } from '@angular/core';
import { ApiService } from '../api.service';
import { AffectationExplanation, DeplacementSimulation } from '../models';

@Injectable({ providedIn: 'root' })
export class PostesApi {
  private readonly api = inject(ApiService);

  /**
   * « Placer » : seats somebody on a seat nobody holds. Refused when the seat
   * is no longer free (409), when a hard rule would break or a lock covers the
   * seat or the person (400, the rule named). Answers the scores before and
   * after, so the caller can warn about what the medium level lost.
   */
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

  place(posteId: string, animateurId: string): Promise<DeplacementSimulation> {
    return this.api.post<DeplacementSimulation>(
      `/api/postes/${encodeURIComponent(posteId)}/placement?animateur=${encodeURIComponent(animateurId)}`,
      null,
    );
  }
}
