// Per-assignment explainability ("Pourquoi lui ?"): calls the /api/postes/*
// endpoints with the currently displayed (already solved) planning, so the
// server can explain one poste without re-solving anything.

import { Injectable, inject } from '@angular/core';
import { ApiService } from './api.service';
import {
  AffectationExplanation,
  DeplacementSimulation,
  PlanningEvenement,
  SuggestionsReparation,
} from './models';

@Injectable({ providedIn: 'root' })
export class AffectationExplanationService {
  private readonly api = inject(ApiService);

  explique(planning: PlanningEvenement, posteId: string): Promise<AffectationExplanation> {
    return this.api.post<AffectationExplanation>(
      `/api/postes/${encodeURIComponent(posteId)}/explication`,
      withoutScore(planning),
    );
  }

  /**
   * Repair assistant (issue #71): the server enumerates the eligible
   * animateurs itself and returns the viable ones, best impact first. Bounded
   * server-side — the answer says how many candidates it actually evaluated.
   */
  suggererReparations(
    planning: PlanningEvenement,
    posteId: string,
  ): Promise<SuggestionsReparation> {
    return this.api.post<SuggestionsReparation>(
      `/api/postes/${encodeURIComponent(posteId)}/suggestions-reparation`,
      withoutScore(planning),
    );
  }

  /**
   * Applies one suggestion to the persisted plan: that seat changes hands and
   * nothing else does. The only call here that writes — hence its own method
   * rather than a flag on the simulation.
   */
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
