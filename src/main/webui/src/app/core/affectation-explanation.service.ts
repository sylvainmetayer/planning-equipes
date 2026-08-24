// Per-assignment explainability ("Pourquoi lui ?"): calls the /api/postes/*
// endpoints with the currently displayed (already solved) planning, so the
// server can explain one poste without re-solving anything.

import { Injectable, inject } from '@angular/core';
import { ApiService } from './api.service';
import { AffectationExplanation, PlanningEvenement, SwapSimulation } from './models';

@Injectable({ providedIn: 'root' })
export class AffectationExplanationService {
  private readonly api = inject(ApiService);

  explique(planning: PlanningEvenement, posteId: string): Promise<AffectationExplanation> {
    return this.api.post<AffectationExplanation>(
      `/api/postes/${encodeURIComponent(posteId)}/explication`,
      withoutScore(planning)
    );
  }

  simulerSwap(planning: PlanningEvenement, posteId: string, animateurCandidatId: string): Promise<SwapSimulation> {
    const url = `/api/postes/${encodeURIComponent(posteId)}/simulation-swap?animateurId=${encodeURIComponent(animateurCandidatId)}`;
    return this.api.post<SwapSimulation>(url, withoutScore(planning));
  }
}

/**
 * The server crashes deserializing a `PlanningEvenement` whose `score` is
 * populated (Quarkus's build-time Jackson codegen for Timefold's
 * `HardMediumSoftScore` calls its private no-arg constructor) — see
 * `AffectationExplanationResourceTest`. `score` is solver-computed output the
 * server never needs back, so it is simply omitted from the request body.
 */
function withoutScore(planning: PlanningEvenement): Omit<PlanningEvenement, 'score'> {
  const { score, ...rest } = planning;
  return rest;
}
