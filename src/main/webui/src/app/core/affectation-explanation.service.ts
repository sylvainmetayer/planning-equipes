// Per-assignment explainability ("Pourquoi lui ?"): calls the /api/postes/*
// endpoints with the currently displayed (already solved) planning, so the
// server can explain one poste without re-solving anything.

import { Injectable, inject } from '@angular/core';
import { ApiService } from './api.service';
import {
  AffectationExplanation,
  PlanningEvenement,
  SuggestionsReparation,
  SwapSimulation
} from './models';

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

  /**
   * Repair assistant (issue #71): the server enumerates the eligible
   * animateurs itself and returns the viable ones, best impact first. Bounded
   * server-side — the answer says how many candidates it actually evaluated.
   */
  suggererReparations(planning: PlanningEvenement, posteId: string): Promise<SuggestionsReparation> {
    return this.api.post<SuggestionsReparation>(
      `/api/postes/${encodeURIComponent(posteId)}/suggestions-reparation`,
      withoutScore(planning)
    );
  }

  /**
   * Applies one suggestion to the persisted plan: that seat changes hands and
   * nothing else does. The only call here that writes — hence its own method
   * rather than a flag on the simulation.
   */
  appliquerReparation(posteId: string, animateurId: string): Promise<void> {
    const url = `/api/postes/${encodeURIComponent(posteId)}/affectation?animateurId=${encodeURIComponent(animateurId)}`;
    return this.api.post<void>(url, null);
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
