// The `/api/stands/*` and `/api/ouvertures-stands/*` endpoints the pages call
// beyond the CRUD — see planning-api.ts for why the paths live here.

import { Injectable, inject } from '@angular/core';
import { ApiService } from '../api.service';
import {
  OpeningLayers,
  ImportGrilleDemande,
  ImportGrilleRapport,
  EmplacementsManquants,
  RapportOuvertures,
  PlageStand,
  RapportSaisieGrille,
  SaisieStandGrille,
  WrittenStand,
} from '../models';

@Injectable({ providedIn: 'root' })
export class StandsApi {
  private readonly api = inject(ApiService);

  downloadGridExample(): Promise<string> {
    return this.api.downloadGet(
      '/api/stands/import-grille/exemple',
      'grille-stands.csv',
      'text/csv',
    );
  }

  /** What the grid import would do, without writing anything. */
  analyseGridImport(demande: ImportGrilleDemande): Promise<ImportGrilleRapport> {
    return this.api.post<ImportGrilleRapport>('/api/stands/import-grille/analyse', demande);
  }

  applyGridImport(demande: ImportGrilleDemande): Promise<ImportGrilleRapport> {
    return this.api.post<ImportGrilleRapport>('/api/stands/import-grille', demande);
  }

  /** The stands without an emplacement, with the one their neighbours suggest (never written). */
  emplacementSuggestions(): Promise<EmplacementsManquants> {
    return this.api.get<EmplacementsManquants>('/api/stands/suggestions-emplacement');
  }

  /** Every stand's openings on every day, as the grid screen shows them. */
  openings(): Promise<RapportOuvertures> {
    return this.api.get<RapportOuvertures>('/api/ouvertures-stands');
  }

  /** The combined calendar of `[du, au]` (`AAAA-MM-JJ`, both included): each stand's layers, day by day. */
  openingLayers(du: string, au: string): Promise<OpeningLayers> {
    const params = new URLSearchParams({ du, au });
    return this.api.get<OpeningLayers>(`/api/ouvertures-stands/couches?${params}`);
  }

  /** The grid as edited, stand by stand — only the stands that changed travel. */
  saveOpeningsGrid(saisie: readonly SaisieStandGrille[]): Promise<RapportSaisieGrille> {
    return this.api.put<RapportSaisieGrille>('/api/ouvertures-stands/grille', { stands: saisie });
  }

  /** Lays one dated plage, typed by hand, on the stand (ADR 0124); answers the stand with its warnings. */
  addPlage(standId: string, plage: PlageStand): Promise<WrittenStand> {
    return this.api.post<WrittenStand>(`/api/stands/${encodeURIComponent(standId)}/plages`, plage);
  }

  /** Removes one plage of the stand. */
  removePlage(standId: string, plageId: number): Promise<WrittenStand> {
    return this.api.deleteReturning<WrittenStand>(
      `/api/stands/${encodeURIComponent(standId)}/plages/${plageId}`,
    );
  }

  /** Removes every plage of the stand on `date` (`AAAA-MM-JJ`): the stand is open all day again. */
  removePlagesOfDay(standId: string, date: string): Promise<WrittenStand> {
    return this.api.deleteReturning<WrittenStand>(
      `/api/stands/${encodeURIComponent(standId)}/plages?date=${encodeURIComponent(date)}`,
    );
  }
}
