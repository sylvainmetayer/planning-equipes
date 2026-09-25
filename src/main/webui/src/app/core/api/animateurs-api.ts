// The `/api/animateurs/*` endpoints the pages call beyond the CRUD that
// `reference-crud.service.ts` already owns — see planning-api.ts for why the
// paths live here and not in the pages.

import { Injectable, inject } from '@angular/core';
import { ApiService } from '../api.service';
import {
  AnimateurProfile,
  ConfirmationView,
  ImportCompetencesDemande,
  ImportCompetencesRapport,
  ImportCsvDemande,
  ImportCsvRapport,
  RapportRelance,
  RapportRenvoi,
  RapportSaisieCompetences,
  RelanceDemande,
  SaisieAnimateurCompetences,
  SyntheseConfirmations,
} from '../models';

@Injectable({ providedIn: 'root' })
export class AnimateursApi {
  private readonly api = inject(ApiService);

  /** Everything known about one animateur, on one page; 404 for an unknown id. */
  profile(animateurId: string): Promise<AnimateurProfile> {
    return this.api.get<AnimateurProfile>(
      `/api/animateurs/${encodeURIComponent(animateurId)}/fiche`,
    );
  }

  /** A new espace link for one animateur; the old one stops working at once. */
  regenerateToken(animateurId: string): Promise<void> {
    return this.api.post<void>(`/api/animateurs/${encodeURIComponent(animateurId)}/token`, null);
  }

  /** Who confirmed their planning, and when. */
  confirmations(): Promise<ConfirmationView[]> {
    return this.api.get<ConfirmationView[]>('/api/animateurs/confirmations');
  }

  /** The same answers in three numbers, next to the date of the last publication. */
  syntheseConfirmations(): Promise<SyntheseConfirmations> {
    return this.api.get<SyntheseConfirmations>('/api/animateurs/confirmations/synthese');
  }

  /** « Relancer maintenant »: sends the confirmation reminder to these animateurs, outside the nightly run. */
  remind(animateurIds: string[]): Promise<RapportRelance> {
    const demande: RelanceDemande = { animateurIds };
    return this.api.post<RapportRelance>('/api/animateurs/relances', demande);
  }

  /** « Renvoyer les envois en échec »: every mail whose last attempt failed for a temporary reason. */
  resendFailed(): Promise<RapportRenvoi> {
    return this.api.post<RapportRenvoi>('/api/animateurs/renvois', null);
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
