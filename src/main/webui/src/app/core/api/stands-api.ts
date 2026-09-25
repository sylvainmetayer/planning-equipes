// The `/api/stands/*` and `/api/ouvertures-stands/*` endpoints the pages call
// beyond the CRUD — see planning-api.ts for why the paths live here.

import { Injectable, inject } from '@angular/core';
import { ApiService } from '../api.service';
import {
  OpeningLayers,
  ImportGrilleDemande,
  ImportGrilleRapport,
  RapportCompactage,
  RapportOuvertures,
  RapportSaisieGrille,
} from '../models';

@Injectable({ providedIn: 'root' })
export class StandsApi {
  private readonly api = inject(ApiService);

  /** Rewrites hand-entered dated windows as the recurring rules they repeat; a preview writes nothing. */
  compactSchedules(apply: boolean): Promise<RapportCompactage> {
    return this.api.post<RapportCompactage>(
      `/api/stands/compactage-horaires?appliquer=${apply}`,
      {},
    );
  }

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
  saveOpeningsGrid(saisie: unknown): Promise<RapportSaisieGrille> {
    return this.api.put<RapportSaisieGrille>('/api/ouvertures-stands/grille', { stands: saisie });
  }
}
