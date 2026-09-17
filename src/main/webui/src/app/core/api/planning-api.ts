// The `/api/planning/*` endpoints, owned here and nowhere else.
//
// A page used to write the URL it called, so renaming a route was a grep over
// string literals in forty files (issue #392, B3). Each resource of the API now
// has one service that knows its paths and its return types; a page asks for
// the thing, not for the address. `scripts/check-api-paths.js` keeps it so.

import { Injectable, inject } from '@angular/core';
import { ApiService } from '../api.service';
import {
  ApercuPublication,
  ComparaisonSnapshots,
  CompteRenduEnvoi,
  HeuresRapport,
  PersistenceStatus,
  RapportEquite,
  RapportTypologies,
  PlanSnapshot,
  PlanningEvenement,
  RapportPublication,
  ResetSummary,
  ScenarioValidationResult,
} from '../models';

@Injectable({ providedIn: 'root' })
export class PlanningApi {
  private readonly api = inject(ApiService);

  /** How many seats the persisted plan holds — zero, and the next solve starts cold. */
  persistedCount(): Promise<PersistenceStatus> {
    return this.api.get<PersistenceStatus>('/api/planning/persisted/count');
  }

  /** The bundled scenario files an import can name. */
  scenarioNames(): Promise<string[]> {
    return this.api.get<string[]>('/api/planning/scenarios');
  }

  /** Downloads the current dataset as a scenario file; resolves to the sentence to show. */
  exportScenario(): Promise<string> {
    return this.api.downloadGet(
      '/api/planning/export-scenario',
      'scenario.yaml',
      'application/x-yaml',
    );
  }

  /** The global PDF, built server-side from the persisted planning. */
  exportGlobalPdf(): Promise<string> {
    return this.api.downloadGet(
      '/api/planning/export/pdf/global',
      'planning-global.pdf',
      'application/pdf',
    );
  }

  /** Every per-animateur document in one archive, built from the planning the browser sends. */
  exportBundle(planning: PlanningEvenement): Promise<string> {
    return this.api.downloadPost(
      '/api/planning/export/bundle/all',
      'planning.zip',
      planning,
      'application/zip',
    );
  }

  /** One animateur's planning as PDF or ICS, from the planning the browser sends — what is exported is what is shown. */
  exportForAnimateur(
    format: 'pdf' | 'ics',
    animateurId: string,
    filename: string,
    planning: PlanningEvenement,
    contentType: string,
  ): Promise<string> {
    const url = `/api/planning/export/${format}/animateur/${encodeURIComponent(animateurId)}`;
    return this.api.downloadPost(url, filename, planning, contentType);
  }

  /** Mails one animateur their planning, built server-side from the persisted plan. */
  sendToAnimateur(animateurId: string): Promise<CompteRenduEnvoi> {
    return this.api.post<CompteRenduEnvoi>(
      `/api/planning/envoi/animateur/${encodeURIComponent(animateurId)}`,
      null,
    );
  }

  /** Who a publication would reach, and what changes for them. */
  publicationPreview(): Promise<ApercuPublication> {
    return this.api.get<ApercuPublication>('/api/planning/publication');
  }

  /** Mails every animateur holding a seat — real mail to real people, confirmed by the caller first. */
  publish(): Promise<RapportPublication> {
    return this.api.post<RapportPublication>('/api/planning/publication', null);
  }

  /** Worked hours against the legal ceilings, recomputed server-side on the planning sent. */
  hoursReport(planning: PlanningEvenement): Promise<HeuresRapport> {
    return this.api.post<HeuresRapport>('/api/planning/hours', planning);
  }

  exportHours(planning: PlanningEvenement): Promise<string> {
    return this.api.downloadPost(
      '/api/planning/hours/export',
      'heures-planning.csv',
      planning,
      'text/csv',
    );
  }

  /** The equity table of the persisted plan, under today's legal parameters — a read-out, never a solve. */
  equityReport(): Promise<RapportEquite> {
    return this.api.get<RapportEquite>('/api/planning/equite');
  }

  exportEquity(): Promise<string> {
    return this.api.downloadGet('/api/planning/equite/export', 'equite-planning.csv', 'text/csv');
  }

  /** The persisted plan read by typologie of jeu — a read-out, never a solve (issue #590). */
  typologiesReport(): Promise<RapportTypologies> {
    return this.api.get<RapportTypologies>('/api/planning/typologies');
  }

  exportTypologies(): Promise<string> {
    return this.api.downloadGet(
      '/api/planning/typologies/export',
      'typologies-planning.csv',
      'text/csv',
    );
  }

  /** Empties the current edition: stands, créneaux, animateurs, seats, ad hoc constraints. */
  reset(): Promise<ResetSummary> {
    return this.api.post<ResetSummary>('/api/planning/reset', {});
  }

  /** The snapshots of every edition — a variant of an edition is another edition. */
  comparableSnapshots(): Promise<PlanSnapshot[]> {
    return this.api.get<PlanSnapshot[]>('/api/planning/snapshots/comparables');
  }

  compareSnapshots(base: string, variante: string): Promise<ComparaisonSnapshots> {
    const params = new URLSearchParams({ base, variante });
    return this.api.get<ComparaisonSnapshots>(`/api/planning/snapshots/compare?${params}`);
  }

  /** Structural validation of a scenario file, without importing anything. */
  validateScenarioFile(yaml: string): Promise<ScenarioValidationResult> {
    return this.api.postRaw<ScenarioValidationResult>(
      '/api/reference-data/valider-scenario-fichier',
      yaml,
      'application/x-yaml',
    );
  }
}
