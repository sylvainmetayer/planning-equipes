// The `/api/animateurs/*` endpoints the pages call beyond the CRUD that
// `reference-crud.service.ts` already owns — see planning-api.ts for why the
// paths live here and not in the pages.

import { Injectable, inject } from '@angular/core';
import { ApiService } from '../api.service';
import {
  ConfirmationView,
  ImportCompetencesDemande,
  ImportCompetencesRapport,
  ImportCsvDemande,
  ImportCsvRapport,
  RapportSaisieCompetences,
  SaisieAnimateurCompetences,
} from '../models';

@Injectable({ providedIn: 'root' })
export class AnimateursApi {
  private readonly api = inject(ApiService);

  /** A new espace link for one animateur; the old one stops working at once. */
  regenerateToken(animateurId: string): Promise<void> {
    return this.api.post<void>(`/api/animateurs/${encodeURIComponent(animateurId)}/token`, null);
  }

  /** Who confirmed their planning, and when. */
  confirmations(): Promise<ConfirmationView[]> {
    return this.api.get<ConfirmationView[]>('/api/animateurs/confirmations');
  }

  downloadCsvExample(): Promise<string> {
    return this.api.downloadGet(
      '/api/animateurs/import-csv/exemple',
      'exemple-animateurs.csv',
      'text/csv',
    );
  }

  /** What the import would do, without writing anything. */
  analyseCsvImport(demande: ImportCsvDemande): Promise<ImportCsvRapport> {
    return this.api.post<ImportCsvRapport>('/api/animateurs/import-csv/analyse', demande);
  }

  applyCsvImport(demande: ImportCsvDemande): Promise<ImportCsvRapport> {
    return this.api.post<ImportCsvRapport>('/api/animateurs/import-csv', demande);
  }

  /** The competences grid as edited — only the animateurs that changed travel, each with their whole map. */
  saveCompetencesGrid(animateurs: SaisieAnimateurCompetences[]): Promise<RapportSaisieCompetences> {
    return this.api.put<RapportSaisieCompetences>('/api/animateurs/competences/grille', {
      animateurs,
    });
  }

  /** The grid as a CSV of ids and levels, in the format the import reads back. */
  downloadCompetencesGrid(): Promise<string> {
    return this.api.downloadGet(
      '/api/animateurs/competences/export',
      'grille-competences.csv',
      'text/csv',
    );
  }

  /** What the competences import would do, without writing anything. */
  analyseCompetencesImport(demande: ImportCompetencesDemande): Promise<ImportCompetencesRapport> {
    return this.api.post<ImportCompetencesRapport>(
      '/api/animateurs/competences/import-grille/analyse',
      demande,
    );
  }

  applyCompetencesImport(demande: ImportCompetencesDemande): Promise<ImportCompetencesRapport> {
    return this.api.post<ImportCompetencesRapport>(
      '/api/animateurs/competences/import-grille',
      demande,
    );
  }
}
